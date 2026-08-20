import { MarkdownView, Notice, TFile, type App } from "obsidian";
import type { EditorView } from "@codemirror/view";
import { DocumentSession } from "./DocumentSession";
import { DocumentIdResolver } from "./DocumentIdResolver";
import { deleteDocument } from "./DocumentDeleteClient";
import { syncCompartment, buildCollabExtension, emptyExtension } from "../editor/syncExtension";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";
import { isConfigured } from "../settings/StoneSyncSettings";
import type { ConnectionStatus } from "../net/StoneSyncSocket";

/** Obsidian exposes the underlying CM6 EditorView as `editor.cm` (unofficial but stable API). */
interface EditorWithCm {
	cm: EditorView;
}

function isSyncableFile(file: TFile | null): file is TFile {
	return !!file && file.extension === "md";
}

export class SyncManager {
	private readonly sessions = new Map<string, DocumentSession>(); // keyed by vault-relative path
	private resolver: DocumentIdResolver | null = null;
	private currentBoundPath: string | null = null;
	private shownUnauthorizedNotice = false;
	private statusListener: ((status: ConnectionStatus | null) => void) | null = null;

	constructor(
		private readonly app: App,
		private getSettings: () => StoneSyncSettings,
		private readonly userName: string,
		private readonly userColor: string
	) {}

	/**
	 * Registers a callback for the connection status of whichever file is currently bound (the
	 * one and only actively-syncing session, see `bindActiveEditor`) - `null` means no file is
	 * currently bound. Used to drive a UI indicator (e.g. a status bar item); at most one
	 * listener is supported, matching the single-consumer (the plugin's own status bar) use case.
	 */
	setStatusListener(listener: ((status: ConnectionStatus | null) => void) | null): void {
		this.statusListener = listener;
	}

	/** Call after settings changes (server URL, API key, vault ID). */
	reconfigure(): void {
		this.resolver = null;
		this.shownUnauthorizedNotice = false;
		this.teardownAll();
	}

	private getResolver(): DocumentIdResolver {
		const settings = this.getSettings();
		if (!this.resolver) {
			this.resolver = new DocumentIdResolver(settings.serverUrl, settings.apiKey, settings.vaultId);
		}
		return this.resolver;
	}

	/** Manual trigger for the "StoneSync: Sync now" command. */
	async syncNow(): Promise<void> {
		const settings = this.getSettings();
		if (!settings.syncEnabled) {
			new Notice("StoneSync: Sync is disabled in settings.");
			return;
		}
		if (!isConfigured(settings)) {
			new Notice("StoneSync: Please set server URL, API key and vault ID in settings first.");
			return;
		}

		const file = this.app.workspace.getActiveFile();
		if (!isSyncableFile(file)) {
			new Notice("StoneSync: No syncable Markdown file active.");
			return;
		}

		try {
			await this.bindActiveEditor();
			new Notice(`StoneSync: Sync started for "${file.path}".`);
		} catch (error) {
			new Notice(`StoneSync: Sync failed – ${errorMessage(error)}`);
		}
	}

	/** On active leaf change: bind the editor of the newly active file to its sync session. */
	async onActiveLeafChange(): Promise<void> {
		const settings = this.getSettings();
		if (!settings.syncEnabled || !isConfigured(settings)) return;
		await this.bindActiveEditor();
	}

	private async bindActiveEditor(): Promise<void> {
		const view = this.app.workspace.getActiveViewOfType(MarkdownView);
		const cm = view ? (view.editor as unknown as EditorWithCm).cm : undefined;
		const file = view?.file ?? null;

		if (!view || !cm || !isSyncableFile(file)) {
			this.unbindCurrent();
			return;
		}

		if (this.currentBoundPath === file.path) return; // already bound
		this.unbindCurrent();

		const session = await this.getOrCreateSession(file);

		// Reconciliation: ensure editor content and Y.Text match before the
		// live binding (yCollab only syncs future changes, no initial
		// reconciliation).
		const localContent = await this.app.vault.read(file);

		// Race guard: during the two awaits above, the user may already have switched to
		// another file (Obsidian reuses the same CM6 editor for the new file). Without this
		// check, file B's editor would get overwritten with file A's content/session ->
		// data corruption. Re-fetch instead of reusing the `view`/`cm` cached above, since
		// they may have already been swapped out in the meantime by a more recent call.
		const stillActiveView = this.app.workspace.getActiveViewOfType(MarkdownView);
		const stillCm = stillActiveView ? (stillActiveView.editor as unknown as EditorWithCm).cm : undefined;
		if (!stillActiveView || !stillCm || stillActiveView.file?.path !== file.path) {
			return;
		}

		if (session.ytext.length === 0) {
			session.seedIfEmpty(localContent);
		} else if (session.ytext.toString() !== stillCm.state.doc.toString()) {
			stillCm.dispatch({
				changes: { from: 0, to: stillCm.state.doc.length, insert: session.ytext.toString() },
			});
		}

		stillCm.dispatch({ effects: syncCompartment.reconfigure(buildCollabExtension(session)) });
		this.currentBoundPath = file.path;
		this.statusListener?.(session.getStatus());

		await session.connect();
	}

	/**
	 * Detaches the sync extension from the current editor AND closes the associated
	 * DocumentSession (WebSocket + Y.Doc + awareness). Without this destroy call, every
	 * file ever opened would keep an open WS connection in the background forever
	 * (resource leak) - acceptable since this MVP only binds to the currently active
	 * editor pane anyway (no multi-pane sync); reconnecting when a file is reopened
	 * is cheap (ticket handshake + delta resync).
	 */
	private unbindCurrent(): void {
		if (!this.currentBoundPath) return;
		const view = this.app.workspace.getActiveViewOfType(MarkdownView);
		const cm = view ? (view.editor as unknown as EditorWithCm).cm : undefined;
		if (cm) {
			cm.dispatch({ effects: syncCompartment.reconfigure(emptyExtension()) });
		}
		const session = this.sessions.get(this.currentBoundPath);
		if (session) {
			session.destroy();
			this.sessions.delete(this.currentBoundPath);
		}
		this.currentBoundPath = null;
		this.statusListener?.(null);
	}

	private async getOrCreateSession(file: TFile): Promise<DocumentSession> {
		const existing = this.sessions.get(file.path);
		if (existing) return existing;

		const settings = this.getSettings();
		const documentId = await this.getResolver().resolve(file.path);

		const session = new DocumentSession({
			documentId,
			serverUrl: settings.serverUrl,
			apiKey: settings.apiKey,
			userName: this.userName,
			userColor: this.userColor,
			onError: (error) => console.error("[StoneSync]", error),
			onStatusChange: (status) => {
				if (status === "unauthorized" && !this.shownUnauthorizedNotice) {
					this.shownUnauthorizedNotice = true;
					new Notice(
						"StoneSync: The API key was rejected by the server (invalid or revoked). " +
							"Please check it in settings. Sync has been stopped."
					);
				}
				if (this.currentBoundPath === file.path) {
					this.statusListener?.(status);
				}
			},
			onRestoreContent: () => {
				new Notice(`StoneSync: "${file.path}" was restored to an earlier version.`);
			},
			onDeleteNotice: () => {
				void this.handleRemoteDeleteNotice(file.path);
			},
		});

		this.sessions.set(file.path, session);
		return session;
	}

	/**
	 * Reacts to the server telling us (via DELETE_NOTICE) that this document was deleted
	 * elsewhere. Removes the local file and tears the session down - mirrors what
	 * `handleDelete` does for a *locally* initiated delete, just triggered from the opposite
	 * direction. Uses the path captured at session-creation time rather than looking it up
	 * again, since by the time this fires the file may already be gone locally too (e.g. if
	 * the user deleted it here as well, in a race with another device's delete).
	 */
	private async handleRemoteDeleteNotice(path: string): Promise<void> {
		if (this.currentBoundPath === path) {
			this.unbindCurrent();
		} else {
			const session = this.sessions.get(path);
			if (session) {
				session.destroy();
				this.sessions.delete(path);
			}
		}
		this.resolver?.forget(path);

		try {
			if (await this.app.vault.adapter.exists(path)) {
				await this.app.vault.adapter.remove(path);
			}
		} catch (error) {
			console.error("[StoneSync] Failed to remove locally deleted file", path, error);
		}
	}

	/** On rename: move the session key + resolver cache along without reconnecting. */
	handleRename(file: TFile, oldPath: string): void {
		const session = this.sessions.get(oldPath);
		if (session) {
			this.sessions.delete(oldPath);
			this.sessions.set(file.path, session);
		}
		this.resolver?.rename(oldPath, file.path);
		if (this.currentBoundPath === oldPath) {
			this.currentBoundPath = file.path;
		}
	}

	/**
	 * Tombstones the document server-side, so other devices actually learn about the
	 * deletion (previously this only tore down the local session/cache, never told the
	 * server at all - the file kept existing for everyone else indefinitely).
	 */
	async handleDelete(path: string): Promise<void> {
		const settings = this.getSettings();
		const documentId = this.resolver?.peekId(path);

		const session = this.sessions.get(path);
		if (session) {
			session.destroy();
			this.sessions.delete(path);
		}
		this.resolver?.forget(path);

		if (!documentId || !isConfigured(settings)) return;
		try {
			await deleteDocument(settings.serverUrl, settings.apiKey, documentId);
		} catch (error) {
			console.error("[StoneSync] Failed to notify server of local delete", path, error);
			new Notice(`StoneSync: Failed to sync deletion of "${path}" - it may reappear.`);
		}
	}

	teardownAll(): void {
		this.unbindCurrent();
		for (const session of this.sessions.values()) {
			session.destroy();
		}
		this.sessions.clear();
	}
}

function errorMessage(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
