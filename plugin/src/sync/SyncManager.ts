import { MarkdownView, Notice, TFile, type App } from "obsidian";
import type { EditorView } from "@codemirror/view";
import { DocumentSession } from "./DocumentSession";
import { DocumentIdResolver } from "./DocumentIdResolver";
import { deleteDocument } from "./DocumentDeleteClient";
import { syncCompartment, buildCollabExtension, emptyExtension } from "../editor/syncExtension";
import { decideReconciliation } from "./reconcile";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";
import { isConfigured } from "../settings/StoneSyncSettings";
import type { ConnectionStatus } from "../net/StoneSyncSocket";

/** Obsidian exposes the underlying CM6 EditorView as `editor.cm` (unofficial but stable API). */
interface EditorWithCm {
	cm: EditorView;
}

/**
 * How long to wait for the server's on-connect history replay before binding the editor anyway.
 * Bounded so an unreachable server never leaves the editor unsynchronized forever - the socket
 * keeps reconnecting in the background and later updates still arrive through the live path.
 */
const CATCH_UP_TIMEOUT_MS = 5000;

/** A collaborator currently present in the bound document (name + cursor color). */
export interface Peer {
	name: string;
	color: string;
}

function isSyncableFile(file: TFile | null): file is TFile {
	return !!file && file.extension === "md";
}

export class SyncManager {
	private readonly sessions = new Map<string, DocumentSession>(); // keyed by vault-relative path
	private resolver: DocumentIdResolver | null = null;
	/** Editor currently carrying the collab extension, per vault-relative path. */
	private readonly boundViews = new Map<string, EditorView>();
	private shownUnauthorizedNotice = false;
	private statusListener: ((status: ConnectionStatus | null) => void) | null = null;
	private presenceListener: ((peers: Peer[]) => void) | null = null;
	private unsubscribePresence: (() => void) | null = null;

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

	/**
	 * Registers a callback for the collaborators currently present in the bound document (live
	 * cursor presence, see `DocumentSession.peers`). Fires on every join/leave/cursor move and
	 * with an empty list when no document is bound.
	 */
	setPresenceListener(listener: ((peers: Peer[]) => void) | null): void {
		this.presenceListener = listener;
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
			await this.bindOpenEditors();
			new Notice(`StoneSync: Sync started for "${file.path}".`);
		} catch (error) {
			new Notice(`StoneSync: Sync failed – ${errorMessage(error)}`);
		}
	}

	/**
	 * Binds every open Markdown editor to its sync session (and tears down the sessions of notes
	 * that are no longer open). Called on active-leaf and layout changes.
	 *
	 * Deliberately not limited to the *active* pane any more: with only the active editor bound,
	 * a note visible in a second pane - or simply open in the background on the other device -
	 * silently stopped receiving collaborators' edits, which is exactly what "I changed it here
	 * and saw nothing over there" looked like in practice.
	 */
	async onActiveLeafChange(): Promise<void> {
		const settings = this.getSettings();
		if (!settings.syncEnabled || !isConfigured(settings)) return;
		await this.bindOpenEditors();
	}

	/** Editors of all currently open Markdown leaves, one entry per distinct file. */
	private openMarkdownEditors(): Array<{ file: TFile; cm: EditorView }> {
		const seen = new Set<string>();
		const result: Array<{ file: TFile; cm: EditorView }> = [];
		for (const leaf of this.app.workspace.getLeavesOfType("markdown")) {
			const view = leaf.view;
			if (!(view instanceof MarkdownView)) continue;
			const file = view.file;
			if (!isSyncableFile(file) || seen.has(file.path)) continue;
			const cm = (view.editor as unknown as EditorWithCm).cm;
			if (!cm) continue;
			// Obsidian keeps multiple views of the same file in sync with each other, so binding
			// the first one is enough - and binding two views to one awareness would make them
			// fight over the local cursor position.
			seen.add(file.path);
			result.push({ file, cm });
		}
		return result;
	}

	private async bindOpenEditors(): Promise<void> {
		const open = this.openMarkdownEditors();
		const openPaths = new Set(open.map((entry) => entry.file.path));

		for (const path of [...this.boundViews.keys()]) {
			if (!openPaths.has(path)) this.unbind(path);
		}

		for (const { file, cm } of open) {
			if (this.boundViews.get(file.path) === cm) continue; // already bound to this very editor
			try {
				await this.bindEditor(file, cm);
			} catch (error) {
				console.error("[StoneSync] Failed to bind editor for", file.path, error);
			}
		}

		this.refreshActiveIndicators();
	}

	private async bindEditor(file: TFile, cm: EditorView): Promise<void> {
		const session = await this.getOrCreateSession(file);
		const localContent = await this.app.vault.read(file);

		// Connect and let the server replay this document's history BEFORE deciding whether the
		// document still needs seeding. Doing it the other way round (the previous behavior) made
		// every device insert its own copy of an already-shared file into the CRDT, so opening the
		// same note on a second device duplicated its entire content instead of syncing it.
		await session.connectAndWaitUntilCaughtUp(CATCH_UP_TIMEOUT_MS);

		// Race guard: during the awaits above the leaf may have been closed or switched to a
		// different file (Obsidian reuses the same CM6 editor for the new file). Writing this
		// file's content into that editor would corrupt the other note, so verify the editor is
		// still showing exactly this file before touching it.
		if (!this.editorStillShows(file, cm)) return;

		const reconciliation = decideReconciliation(session.ytext.toString(), cm.state.doc.toString());
		if (reconciliation.action === "seed") {
			session.seedIfEmpty(reconciliation.content || localContent);
		} else if (reconciliation.action === "replaceEditor") {
			cm.dispatch({ changes: { from: 0, to: cm.state.doc.length, insert: reconciliation.content } });
		}

		cm.dispatch({ effects: syncCompartment.reconfigure(buildCollabExtension(session)) });
		this.boundViews.set(file.path, cm);
	}

	private editorStillShows(file: TFile, cm: EditorView): boolean {
		return this.openMarkdownEditors().some((entry) => entry.file.path === file.path && entry.cm === cm);
	}

	/**
	 * Pushes connection status + collaborator presence of the *active* note to the UI listeners.
	 * Sessions for background notes keep syncing, they just aren't what the status bar describes.
	 */
	private refreshActiveIndicators(): void {
		const activePath = this.app.workspace.getActiveFile()?.path ?? null;
		const session = activePath ? this.sessions.get(activePath) : undefined;

		this.unsubscribePresence?.();
		this.unsubscribePresence = null;

		if (!session || !activePath || !this.boundViews.has(activePath)) {
			this.statusListener?.(null);
			this.presenceListener?.([]);
			return;
		}

		this.statusListener?.(session.getStatus());
		const emit = () => this.presenceListener?.(session.peers());
		this.unsubscribePresence = session.onPeersChange(emit);
		emit();
	}

	/**
	 * Detaches the sync extension from one note's editor AND closes its DocumentSession
	 * (WebSocket + Y.Doc + awareness). Without this, every file ever opened would keep an open
	 * WS connection around forever; reconnecting when a note is reopened is cheap (ticket
	 * handshake + delta resync).
	 */
	private unbind(path: string): void {
		const cm = this.boundViews.get(path);
		if (cm) {
			try {
				cm.dispatch({ effects: syncCompartment.reconfigure(emptyExtension()) });
			} catch (error) {
				// The editor may already be destroyed together with its (now closed) leaf.
				console.debug("[StoneSync] Could not detach sync extension for", path, error);
			}
		}
		this.boundViews.delete(path);

		const session = this.sessions.get(path);
		if (session) {
			session.destroy();
			this.sessions.delete(path);
		}
	}

	private unbindAll(): void {
		for (const path of [...this.boundViews.keys()]) {
			this.unbind(path);
		}
		this.unsubscribePresence?.();
		this.unsubscribePresence = null;
		this.statusListener?.(null);
		this.presenceListener?.([]);
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
				if (this.app.workspace.getActiveFile()?.path === file.path) {
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
	 * elsewhere. Tears the session down and removes the local file - mirrors what
	 * `handleDelete` does for a *locally* initiated delete, just triggered from the opposite
	 * direction. Uses the path captured at session-creation time rather than looking it up
	 * again, since by the time this fires the file may already be gone locally too (e.g. if
	 * the user deleted it here as well, in a race with another device's delete).
	 *
	 * If this happens to be the file the user currently has open, the local content is kept
	 * instead of being pulled out from under an active editor (found via agy architecture
	 * review, same reasoning as `VaultEventsManager`'s delete reaction) - the live sync session
	 * still tears down either way, since the document is tombstoned server-side regardless.
	 * Uses Obsidian's trash rather than a hard filesystem delete, so an unlucky race is still
	 * recoverable.
	 */
	private async handleRemoteDeleteNotice(path: string): Promise<void> {
		const wasActiveFile = this.app.workspace.getActiveFile()?.path === path;

		this.unbind(path);
		this.refreshActiveIndicators();
		this.resolver?.forget(path);

		if (wasActiveFile) {
			new Notice(
				`StoneSync: A collaborator deleted "${path}", but it's kept since you have it open. ` +
					"Close it and delete manually if you agree it should go."
			);
			return;
		}

		try {
			const file = this.app.vault.getAbstractFileByPath(path);
			if (file) {
				await this.app.vault.trash(file, true);
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
		const boundView = this.boundViews.get(oldPath);
		if (boundView) {
			this.boundViews.delete(oldPath);
			this.boundViews.set(file.path, boundView);
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

		this.unbind(path);
		this.refreshActiveIndicators();
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
		this.unbindAll();
		for (const session of this.sessions.values()) {
			session.destroy();
		}
		this.sessions.clear();
	}
}

function errorMessage(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
