import { MarkdownView, Notice, TFile, type App } from "obsidian";
import type { EditorView } from "@codemirror/view";
import { DocumentSession } from "./DocumentSession";
import { DocumentIdResolver } from "./DocumentIdResolver";
import { syncCompartment, buildCollabExtension, emptyExtension } from "../editor/syncExtension";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";
import { isConfigured } from "../settings/StoneSyncSettings";

/** Obsidian exponiert die zugrundeliegende CM6-EditorView als `editor.cm` (inoffizielle, aber stabile API). */
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

	constructor(
		private readonly app: App,
		private getSettings: () => StoneSyncSettings,
		private readonly userName: string,
		private readonly userColor: string
	) {}

	/** Nach Settings-Änderungen (Server-URL, API-Key, Vault-ID) aufrufen. */
	reconfigure(): void {
		this.resolver = null;
		this.teardownAll();
	}

	private getResolver(): DocumentIdResolver {
		const settings = this.getSettings();
		if (!this.resolver) {
			this.resolver = new DocumentIdResolver(settings.serverUrl, settings.apiKey, settings.vaultId);
		}
		return this.resolver;
	}

	/** Manueller Trigger für den Command "StoneSync: Sync now". */
	async syncNow(): Promise<void> {
		const settings = this.getSettings();
		if (!settings.syncEnabled) {
			new Notice("StoneSync: Sync ist in den Einstellungen deaktiviert.");
			return;
		}
		if (!isConfigured(settings)) {
			new Notice("StoneSync: Bitte zuerst Server-URL, API-Key und Vault-ID in den Einstellungen setzen.");
			return;
		}

		const file = this.app.workspace.getActiveFile();
		if (!isSyncableFile(file)) {
			new Notice("StoneSync: Keine synchronisierbare Markdown-Datei aktiv.");
			return;
		}

		try {
			await this.bindActiveEditor();
			new Notice(`StoneSync: Sync für "${file.path}" gestartet.`);
		} catch (error) {
			new Notice(`StoneSync: Sync fehlgeschlagen – ${errorMessage(error)}`);
		}
	}

	/** Bei aktivem Blattwechsel: Editor der neuen aktiven Datei an ihre Sync-Session binden. */
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

		if (this.currentBoundPath === file.path) return; // schon gebunden
		this.unbindCurrent();

		const session = await this.getOrCreateSession(file);

		// Reconciliation: sicherstellen, dass Editor-Inhalt und Y.Text vor dem
		// Live-Binding übereinstimmen (yCollab synct nur zukünftige Änderungen,
		// keine initiale Abgleichung).
		const localContent = await this.app.vault.read(file);
		if (session.ytext.length === 0) {
			session.seedIfEmpty(localContent);
		} else if (session.ytext.toString() !== cm.state.doc.toString()) {
			cm.dispatch({
				changes: { from: 0, to: cm.state.doc.length, insert: session.ytext.toString() },
			});
		}

		cm.dispatch({ effects: syncCompartment.reconfigure(buildCollabExtension(session)) });
		this.currentBoundPath = file.path;

		await session.connect();
	}

	private unbindCurrent(): void {
		if (!this.currentBoundPath) return;
		const view = this.app.workspace.getActiveViewOfType(MarkdownView);
		const cm = view ? (view.editor as unknown as EditorWithCm).cm : undefined;
		if (cm) {
			cm.dispatch({ effects: syncCompartment.reconfigure(emptyExtension()) });
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
		});

		this.sessions.set(file.path, session);
		return session;
	}

	/** Beim Umbenennen: Session-Key + Resolver-Cache mitziehen, ohne neu zu verbinden. */
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
