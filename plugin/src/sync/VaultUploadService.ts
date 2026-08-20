import { Notice, TFile, type App } from "obsidian";
import { DocumentSession } from "./DocumentSession";
import { DocumentIdResolver } from "./DocumentIdResolver";
import { AttachmentSync } from "../attachments/AttachmentSync";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";
import { isConfigured } from "../settings/StoneSyncSettings";

export interface VaultUploadOptions {
	app: App;
	settings: StoneSyncSettings;
	userName: string;
	userColor: string;
	/** Fired after every file is processed - drives UI other than the periodic Notice (e.g. the status bar). */
	onProgress?: (processed: number, total: number) => void;
}

/**
 * Bulk vault upload: the mirror image of `VaultDownloadService`, for the opposite starting
 * point - an existing local vault (e.g. years of notes already in Obsidian) being connected to
 * a freshly created, still-empty server vault for the first time. Without this, the only way
 * for existing content to ever reach the server was opening every single file at least once
 * (`SyncManager.bindActiveEditor`'s `seedIfEmpty` call) - impractical for a vault with any real
 * amount of content.
 *
 * For every local Markdown file, resolves its documentId, opens a headless `DocumentSession`,
 * and seeds the remote `Y.Text` with the local content - a no-op if the remote side already has
 * content (never overwrites existing remote content, mirroring the download side's "never
 * overwrite local" rule). For every other local file (attachments), reuses `AttachmentSync`,
 * which already only uploads when the content hash is unknown to the server.
 */
export class VaultUploadService {
	private readonly resolver: DocumentIdResolver;

	constructor(private readonly options: VaultUploadOptions) {
		this.resolver = new DocumentIdResolver(options.settings.serverUrl, options.settings.apiKey, options.settings.vaultId);
	}

	async uploadEntireVault(): Promise<void> {
		const { app, settings } = this.options;
		if (!isConfigured(settings)) {
			new Notice("StoneSync: Please set server URL, API key and vault ID in settings first.");
			return;
		}

		const markdownFiles = app.vault.getMarkdownFiles();
		const attachmentFiles = app.vault.getFiles().filter((file) => file.extension !== "md");
		const total = markdownFiles.length + attachmentFiles.length;

		let uploaded = 0;
		let skipped = 0;
		let failed = 0;

		for (const file of markdownFiles) {
			try {
				if (await this.uploadTextFile(file)) uploaded++;
				else skipped++;
			} catch (error) {
				failed++;
				console.error("[StoneSync] Failed to upload document during bulk vault upload", file.path, error);
			}
			this.reportProgress(uploaded, skipped, failed, total);
		}

		const attachmentSync = new AttachmentSync({
			serverUrl: settings.serverUrl,
			apiKey: settings.apiKey,
			vaultId: settings.vaultId,
			adapter: app.vault.adapter,
			documentIdResolver: this.resolver,
		});
		for (const file of attachmentFiles) {
			try {
				const result = await attachmentSync.syncFile(file.path);
				if (result.uploaded) uploaded++;
				else skipped++;
			} catch (error) {
				failed++;
				console.error("[StoneSync] Failed to upload attachment during bulk vault upload", file.path, error);
			}
			this.reportProgress(uploaded, skipped, failed, total);
		}

		new Notice(
			`StoneSync: Vault upload finished - ${uploaded} uploaded, ${skipped} skipped (already on server)` +
				(failed > 0 ? `, ${failed} failed.` : ".")
		);
	}

	/** Returns whether this file's content was actually seeded (false = remote already had content). */
	private async uploadTextFile(file: TFile): Promise<boolean> {
		const { app, settings, userName, userColor } = this.options;
		const documentId = await this.resolver.resolve(file.path, "TEXT");
		const localContent = await app.vault.read(file);

		const session = new DocumentSession({
			documentId,
			serverUrl: settings.serverUrl,
			apiKey: settings.apiKey,
			userName,
			userColor,
			onError: (error) => console.error("[StoneSync]", error),
		});

		try {
			const caughtUp = session.waitUntilCaughtUp();
			await session.connect();
			await caughtUp;

			const wasEmpty = session.ytext.length === 0;
			session.seedIfEmpty(localContent);
			return wasEmpty && localContent.length > 0;
		} finally {
			session.destroy();
		}
	}

	private reportProgress(uploaded: number, skipped: number, failed: number, total: number): void {
		const processed = uploaded + skipped + failed;
		this.options.onProgress?.(processed, total);
		if (processed % 10 === 0 || processed === total) {
			new Notice(`StoneSync: ${processed}/${total} files processed (${uploaded} uploaded, ${skipped} skipped).`);
		}
	}
}
