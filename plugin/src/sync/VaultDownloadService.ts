import { Notice, type App } from "obsidian";
import { listDocuments } from "./DocumentListClient";
import { downloadAttachment } from "../attachments/AttachmentDownload";
import { DocumentSession } from "./DocumentSession";
import { ensureParentFolders } from "./ensureParentFolders";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";
import { isConfigured } from "../settings/StoneSyncSettings";

export interface VaultDownloadOptions {
	app: App;
	settings: StoneSyncSettings;
	userName: string;
	userColor: string;
}

/**
 * Bulk vault download: fetches the full list of non-deleted documents for the configured
 * vault and, for every one whose local file doesn't exist yet, materializes it - a `TEXT`
 * document via a headless `DocumentSession` (connect, wait for the on-connect history replay
 * to finish, read the resulting `Y.Text`), an `ATTACHMENT` document via a direct byte download.
 *
 * This is what makes "colleague opens Obsidian with the plugin pre-configured and gets the
 * entire existing vault" possible - without it, a freshly connected client only ever sees
 * whichever individual files it happens to open one by one.
 *
 * Never overwrites a file that already exists locally (last-writer-wins is not attempted here;
 * a pre-existing local file is assumed intentional and is skipped with a warning instead of
 * risking silently clobbering local edits).
 */
export class VaultDownloadService {
	constructor(private readonly options: VaultDownloadOptions) {}

	async downloadEntireVault(): Promise<void> {
		const { app, settings } = this.options;
		if (!isConfigured(settings)) {
			new Notice("StoneSync: Please set server URL, API key and vault ID in settings first.");
			return;
		}

		let documents;
		try {
			documents = await listDocuments(settings.serverUrl, settings.apiKey, settings.vaultId);
		} catch (error) {
			new Notice(`StoneSync: Failed to list vault documents - ${errorMessage(error)}`);
			return;
		}

		let downloaded = 0;
		let skipped = 0;
		let failed = 0;
		const total = documents.length;

		for (const document of documents) {
			try {
				const alreadyExists = await app.vault.adapter.exists(document.path);
				if (alreadyExists) {
					skipped++;
					continue;
				}

				if (document.contentType === "TEXT") {
					await this.downloadTextDocument(document.id, document.path);
				} else {
					await this.downloadAttachmentDocument(document.id, document.path);
				}
				downloaded++;
			} catch (error) {
				failed++;
				console.error("[StoneSync] Failed to download document during bulk vault download", document.path, error);
			}

			const processed = downloaded + skipped + failed;
			if (processed % 10 === 0 || processed === total) {
				new Notice(`StoneSync: ${processed}/${total} files processed (${downloaded} downloaded, ${skipped} skipped).`);
			}
		}

		new Notice(
			`StoneSync: Vault download finished - ${downloaded} downloaded, ${skipped} skipped (already existed)` +
				(failed > 0 ? `, ${failed} failed.` : ".")
		);
	}

	private async downloadTextDocument(documentId: string, path: string): Promise<void> {
		const { app, settings, userName, userColor } = this.options;
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

			await ensureParentFolders(app.vault.adapter, path);
			if (await app.vault.adapter.exists(path)) return; // race guard: created concurrently meanwhile
			await app.vault.adapter.write(path, session.ytext.toString());
		} finally {
			session.destroy();
		}
	}

	private async downloadAttachmentDocument(documentId: string, path: string): Promise<void> {
		const { app, settings } = this.options;
		const bytes = await downloadAttachment(settings.serverUrl, settings.apiKey, documentId);

		await ensureParentFolders(app.vault.adapter, path);
		if (await app.vault.adapter.exists(path)) return; // race guard: created concurrently meanwhile
		await app.vault.adapter.writeBinary(path, bytes);
	}
}

function errorMessage(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
