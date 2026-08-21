import type { App } from "obsidian";
import { downloadAttachment } from "../attachments/AttachmentDownload";
import { DocumentSession } from "./DocumentSession";
import { ensureParentFolders } from "./ensureParentFolders";
import type { StoneSyncSettings } from "../settings/StoneSyncSettings";
import { wasRecentlyDeleted } from "./recentlyDeleted";

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
function isDeletePending(settings: StoneSyncSettings, path: string): boolean {
	return (settings.pendingDeletePaths ?? []).includes(path);
}

export async function downloadTextDocument(options: DocumentDownloaderOptions, documentId: string, path: string): Promise<void> {
	const { app, settings, userName, userColor } = options;
	if (await app.vault.adapter.exists(path)) return;
	if (wasRecentlyDeleted(path)) return; // see recentlyDeleted.ts
	if (isDeletePending(settings, path)) return; // not yet confirmed deleted server-side - do not resurrect it

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
		// Race guards: created concurrently meanwhile, or deleted while this download was in
		// flight (the network round-trip above is exactly the window that race needs).
		if (await app.vault.adapter.exists(path)) return;
		if (wasRecentlyDeleted(path)) return;
		if (isDeletePending(settings, path)) return;
		await app.vault.adapter.write(path, session.ytext.toString());
	} finally {
		session.destroy();
	}
}

export async function downloadAttachmentDocument(options: DocumentDownloaderOptions, documentId: string, path: string): Promise<void> {
	const { app, settings } = options;
	if (await app.vault.adapter.exists(path)) return;
	if (wasRecentlyDeleted(path)) return; // see recentlyDeleted.ts
	if (isDeletePending(settings, path)) return;

	const bytes = await downloadAttachment(settings.serverUrl, settings.apiKey, documentId);

	await ensureParentFolders(app.vault.adapter, path);
	if (await app.vault.adapter.exists(path)) return; // race guard: created concurrently meanwhile
	if (wasRecentlyDeleted(path)) return; // race guard: deleted while this download was in flight
	if (isDeletePending(settings, path)) return;
	await app.vault.adapter.writeBinary(path, bytes);
}
