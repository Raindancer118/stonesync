import { Notice, type App } from "obsidian";
import { VaultEventsSocket, type VaultEvent } from "../net/VaultEventsSocket";
import { requestTicket } from "../net/TicketClient";
import { getClientSessionId } from "../net/clientSession";
import { downloadTextDocument, downloadAttachmentDocument } from "./DocumentDownloader";
import { listDocuments } from "./DocumentListClient";
import { toWebSocketBaseUrl, isConfigured, type StoneSyncSettings } from "../settings/StoneSyncSettings";
import { pickUserColor } from "../settings/userColor";
import { pathsRemovedSincePreviousSnapshot } from "./reconcileRemovals";

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
		/** Re-reads permissions and rebinds open editors after the server reports a change. */
		private readonly onAccessChanged: () => Promise<void> = async () => {},
		/** Applies a queued cross-vault link repair to a note this client has open. */
		private readonly onLinkRewrite: (documentId: string) => Promise<void> = async () => {},
		/**
		 * Retries any deletes that couldn't reach the server while offline - see
		 * `SyncManager.flushPendingDeletes`. Awaited BEFORE reconciliation on every (re)connect:
		 * a delete that's still only queued would otherwise still be listed by the server, and
		 * reconciliation would re-download the very file being deleted.
		 */
		private readonly onReconnected: () => Promise<void> = async () => {},
		/** Persists `settings.knownServerPaths` after a reconciliation pass - see
		 * {@link reconcileMissingFiles}. */
		private readonly persistKnownPaths: (paths: string[]) => Promise<void> = async () => {}
	) {}

	/** Read live on every call - see {@code SyncManager.currentUserName} for why. */
	private currentUserName(): string {
		return this.getSettings().displayName;
	}

	private currentUserColor(): string {
		return pickUserColor(this.currentUserName());
	}

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
					// successful (re)connect reconciles: downloads whatever is missing locally,
					// AND (see reconcileMissingFiles) removes local files a collaborator deleted
					// server-side while this client was gone - flushing this client's own queued
					// offline deletes first, so they don't win a race against reconciliation
					// re-downloading the very file being deleted.
					void this.onReconnected().then(() => this.reconcileMissingFiles());
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

	/**
	 * Downloads whatever the server has that this client is missing, AND - the part that closes
	 * the "I deleted 10 files while a colleague was offline and they never found out" gap -
	 * removes local files the server no longer has, by comparing against a snapshot of what this
	 * client last saw (`settings.knownServerPaths`).
	 *
	 * That snapshot, not "path exists locally but not in the current server list", is the basis
	 * for removal: a path missing from the very first-ever server list this client sees could
	 * just as easily be local content that was never uploaded yet (this is the plugin's own
	 * pre-existing vault, about to be pushed with `uploadEntireVault`) as a real deletion - there
	 * is no way to tell those apart without a prior snapshot to diff against. With no snapshot
	 * yet (`knownServerPaths` is `undefined`), only additive reconciliation runs, same as before
	 * this feature existed.
	 */
	private async reconcileMissingFiles(): Promise<void> {
		const settings = this.getSettings();
		try {
			const documents = await listDocuments(settings.serverUrl, settings.apiKey, settings.vaultId);
			const currentPaths = documents.map((document) => document.path);

			for (const path of pathsRemovedSincePreviousSnapshot(settings.knownServerPaths, currentPaths)) {
				this.enqueueTask(() => this.removeLocallyIfPresent(path, "was deleted while you were offline"));
			}

			for (const document of documents) {
				this.enqueueTask(async () => {
					const downloaderOptions = { app: this.app, settings, userName: this.currentUserName(), userColor: this.currentUserColor() };
					if (document.contentType === "TEXT") {
						await downloadTextDocument(downloaderOptions, document.id, document.path);
					} else {
						await downloadAttachmentDocument(downloaderOptions, document.id, document.path);
					}
				});
			}

			await this.persistKnownPaths(currentPaths);
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
		const downloaderOptions = { app: this.app, settings, userName: this.currentUserName(), userColor: this.currentUserColor() };

		if (event.type === "document_created") {
			if (await this.app.vault.adapter.exists(event.path)) return;
			if (event.contentType === "TEXT") {
				await downloadTextDocument(downloaderOptions, event.documentId, event.path);
			} else {
				await downloadAttachmentDocument(downloaderOptions, event.documentId, event.path);
			}
			new Notice(`StoneSync: "${event.path}" was added by a collaborator.`);
		} else if (event.type === "document_deleted") {
			await this.removeLocallyIfPresent(event.path, "was deleted by a collaborator");
		} else if (event.type === "link_rewrite") {
			await this.onLinkRewrite(event.documentId);
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

	/**
	 * Shared by the live "document_deleted" event and the reconciliation-time removal pass
	 * (see {@link reconcileMissingFiles}): removes a locally present file that the server no
	 * longer has, unless the user has it open right now.
	 */
	private async removeLocallyIfPresent(path: string, reason: string): Promise<void> {
		const file = this.app.vault.getAbstractFileByPath(path);
		if (!file) return; // our own action, already gone (or never existed here)

		// A collaborator's deletion is jarring and, worse, silently destructive if the user is
		// mid-edit - keep the file and just tell them, rather than deleting content out from
		// under an open editor (found via agy architecture review).
		if (this.app.workspace.getActiveFile()?.path === path) {
			new Notice(
				`StoneSync: A collaborator deleted "${path}", but it's kept since you have it open. ` +
					"Close it and delete manually if you agree it should go."
			);
			return;
		}

		// Obsidian's trash (not a hard filesystem delete) so an unlucky race with the user's own
		// concurrent action is recoverable from Obsidian's own trash, not just gone.
		await this.app.vault.trash(file, true);
		new Notice(`StoneSync: "${path}" ${reason}.`);
	}
}
