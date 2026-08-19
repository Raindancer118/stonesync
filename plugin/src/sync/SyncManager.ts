import { MarkdownView, Notice, TFile, type App } from "obsidian";
import type { EditorView } from "@codemirror/view";
import { DocumentSession } from "./DocumentSession";
import { DocumentIdResolver } from "./DocumentIdResolver";
import { syncCompartment, buildCollabExtension, emptyExtension } from "../editor/syncExtension";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";
import { isConfigured } from "../settings/StoneSyncSettings";

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

	constructor(
		private readonly app: App,
		private getSettings: () => StoneSyncSettings,
		private readonly userName: string,
		private readonly userColor: string
	) {}

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
			},
		});

		this.sessions.set(file.path, session);
		return session;
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

	handleDelete(path: string): void {
		const session = this.sessions.get(path);
		if (session) {
			session.destroy();
			this.sessions.delete(path);
		}
		this.resolver?.forget(path);
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
