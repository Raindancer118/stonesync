import type { App } from "obsidian";
import { downloadAttachment } from "../attachments/AttachmentDownload";
import { DocumentSession } from "./DocumentSession";
import { ensureParentFolders } from "./ensureParentFolders";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";

export interface DocumentDownloaderOptions {
	app: App;
	settings: StoneSyncSettings;
	userName: string;
	userColor: string;
}

/**
 * Downloads a single document (by id + vault-relative path) into the local vault - shared by
 * `VaultDownloadService` (bulk, on-demand) and the live vault-events reactor (one file at a
 * time, as a colleague's "document_created" event arrives). Never overwrites a file that already
 * exists locally.
 */
export async function downloadTextDocument(options: DocumentDownloaderOptions, documentId: string, path: string): Promise<void> {
	const { app, settings, userName, userColor } = options;
	if (await app.vault.adapter.exists(path)) return;

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

export async function downloadAttachmentDocument(options: DocumentDownloaderOptions, documentId: string, path: string): Promise<void> {
	const { app, settings } = options;
	if (await app.vault.adapter.exists(path)) return;

	const bytes = await downloadAttachment(settings.serverUrl, settings.apiKey, documentId);

	await ensureParentFolders(app.vault.adapter, path);
	if (await app.vault.adapter.exists(path)) return; // race guard: created concurrently meanwhile
	await app.vault.adapter.writeBinary(path, bytes);
}
