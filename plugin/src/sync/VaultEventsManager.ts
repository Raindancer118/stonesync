import { Notice, type App } from "obsidian";
import { VaultEventsSocket, type VaultEvent } from "../net/VaultEventsSocket";
import { requestTicket } from "../net/TicketClient";
import { getClientSessionId } from "../net/clientSession";
import { downloadTextDocument, downloadAttachmentDocument } from "./DocumentDownloader";
import { listDocuments } from "./DocumentListClient";
import { toWebSocketBaseUrl, isConfigured, type StoneSyncSettings } from "../settings/StoneSyncSettings";

/** Caps how many downloads run at once - a burst of many document_created events (e.g. a
 * colleague dragging a whole folder in) must not fire off hundreds of concurrent requests/Yjs
 * sessions at once (found via agy architecture review: a "thundering herd" would flood the
 * event loop and could overwhelm the server too). */
const MAX_CONCURRENT_REACTIONS = 4;

/**
 * Owns the single, persistent vault-events connection (see `VaultEventsSocket`) and reacts to
 * what it reports: a colleague's new document gets auto-downloaded, a colleague's deleted
 * document gets removed locally - all without needing this document open, or one Yjs session
 * per file in the vault. This is the "know in real time when someone deletes or adds a file"
 * feature, deliberately kept separate from per-file live editing sync.
 *
 * Self-caused events are filtered by `originSessionId` (see `net/clientSession.ts`) rather than
 * by pausing the reactor during a bulk operation - pausing would risk missing a genuine
 * collaborator event that happens to arrive during that window (found via agy architecture
 * review). Reactions run through a small concurrency-limited queue, and every (re)connect
 * triggers a one-shot reconciliation download pass, since a WebSocket only delivers events while
 * it's actually connected - time spent offline/reconnecting would otherwise be a silent gap.
 */
export class VaultEventsManager {
	private socket: VaultEventsSocket | null = null;
	private shownUnauthorizedNotice = false;
	private activeReactions = 0;
	private readonly pendingReactions: Array<() => Promise<void>> = [];

	constructor(
		private readonly app: App,
		private readonly getSettings: () => StoneSyncSettings,
		private readonly userName: string,
		private readonly userColor: string,
		/** Re-reads permissions and rebinds open editors after the server reports a change. */
		private readonly onAccessChanged: () => Promise<void> = async () => {}
	) {}

	start(): void {
		this.stop();
		this.shownUnauthorizedNotice = false;
		const settings = this.getSettings();
		if (!settings.syncEnabled || !isConfigured(settings)) return;

		this.socket = new VaultEventsSocket({
			wsBaseUrl: toWebSocketBaseUrl(settings.serverUrl),
			vaultId: settings.vaultId,
			getTicket: () => requestTicket(settings.serverUrl, settings.apiKey),
			onEvent: (event) => this.enqueue(event),
			onError: (error) => console.error("[StoneSync]", error),
			onStatusChange: (status) => {
				if (status === "connected") {
					// Closes the "disconnect sync-hole": events that happened while this client
					// was offline/reconnecting were never delivered and never will be, so every
					// successful (re)connect reconciles by downloading whatever is missing
					// locally. Deliberately additive-only (never removes local files), matching
					// this project's existing bias against destructive auto-actions.
					void this.reconcileMissingFiles();
				}
				if (status === "unauthorized" && !this.shownUnauthorizedNotice) {
					this.shownUnauthorizedNotice = true;
					new Notice(
						"StoneSync: The API key was rejected by the server (invalid or revoked). " +
							"Real-time notifications about collaborators' changes have stopped."
					);
				}
			},
		});
		void this.socket.connect();
	}

	stop(): void {
		this.socket?.destroy();
		this.socket = null;
		this.pendingReactions.length = 0;
	}

	private async reconcileMissingFiles(): Promise<void> {
		const settings = this.getSettings();
		try {
			const documents = await listDocuments(settings.serverUrl, settings.apiKey, settings.vaultId);
			for (const document of documents) {
				this.enqueueTask(async () => {
					const downloaderOptions = { app: this.app, settings, userName: this.userName, userColor: this.userColor };
					if (document.contentType === "TEXT") {
						await downloadTextDocument(downloaderOptions, document.id, document.path);
					} else {
						await downloadAttachmentDocument(downloaderOptions, document.id, document.path);
					}
				});
			}
		} catch (error) {
			console.error("[StoneSync] Failed to reconcile vault state after (re)connect", error);
		}
	}

	private enqueue(event: VaultEvent): void {
		if (event.originSessionId === getClientSessionId()) return; // our own action, already handled locally
		this.enqueueTask(() => this.react(event));
	}

	/** Runs at most `MAX_CONCURRENT_REACTIONS` tasks at once, queuing the rest. */
	private enqueueTask(task: () => Promise<void>): void {
		this.pendingReactions.push(task);
		this.pump();
	}

	private pump(): void {
		while (this.activeReactions < MAX_CONCURRENT_REACTIONS && this.pendingReactions.length > 0) {
			const task = this.pendingReactions.shift();
			if (!task) break;
			this.activeReactions++;
			task()
				.catch((error) => console.error("[StoneSync] Failed to process a queued vault-events reaction", error))
				.finally(() => {
					this.activeReactions--;
					this.pump();
				});
		}
	}

	private async react(event: VaultEvent): Promise<void> {
		const settings = this.getSettings();
		const downloaderOptions = { app: this.app, settings, userName: this.userName, userColor: this.userColor };

		if (event.type === "document_created") {
			if (await this.app.vault.adapter.exists(event.path)) return;
			if (event.contentType === "TEXT") {
				await downloadTextDocument(downloaderOptions, event.documentId, event.path);
			} else {
				await downloadAttachmentDocument(downloaderOptions, event.documentId, event.path);
			}
			new Notice(`StoneSync: "${event.path}" was added by a collaborator.`);
		} else if (event.type === "document_deleted") {
			const file = this.app.vault.getAbstractFileByPath(event.path);
			if (!file) return; // our own action, already gone

			// A colleague deleting a file the user is actively looking at is jarring and, worse,
			// silently destructive if they're mid-edit - keep the file and just tell them,
			// rather than deleting content out from under an open editor (found via agy
			// architecture review).
			if (this.app.workspace.getActiveFile()?.path === event.path) {
				new Notice(
					`StoneSync: A collaborator deleted "${event.path}", but it's kept since you have it open. ` +
						"Close it and delete manually if you agree it should go."
				);
				return;
			}

			// Obsidian's trash (not a hard filesystem delete) so an unlucky race with the user's
			// own concurrent action is recoverable from Obsidian's own trash, not just gone.
			await this.app.vault.trash(file, true);
			new Notice(`StoneSync: "${event.path}" was deleted by a collaborator.`);
		} else if (event.type === "access_revoked") {
			// Revoking access has to actually take the content off this device - otherwise the
			// copy that is already here stays readable forever and only stops receiving updates.
			// Editors are rebound first, so an open note becomes read-only/unsynced immediately.
			await this.onAccessChanged();

			const file = this.app.vault.getAbstractFileByPath(event.path);
			if (!file) return;
			await this.app.vault.trash(file, true);
			new Notice(
				`StoneSync: You no longer have access to "${event.path}" - the local copy was moved to Obsidian's trash.`
			);
		}
	}
}
