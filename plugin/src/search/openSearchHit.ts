import { Notice, TFile } from "obsidian";
import type { SearchHit } from "./SearchClient";
import { downloadTextDocument, downloadAttachmentDocument, type DocumentDownloaderOptions } from "../sync/DocumentDownloader";

/**
 * Opens a search result, downloading it first (via the same downloader used by bulk vault
 * download and the live vault-events reactor) if this device doesn't have it locally yet - a
 * search result the user picked is exactly the kind of "I want this now" moment where silently
 * requiring a separate manual download step first would be a bad experience.
 */
export async function openSearchHit(options: DocumentDownloaderOptions, hit: SearchHit): Promise<void> {
	const { app } = options;

	if (!(app.vault.getAbstractFileByPath(hit.path) instanceof TFile)) {
		try {
			if (hit.contentType === "TEXT") {
				await downloadTextDocument(options, hit.id, hit.path);
			} else {
				await downloadAttachmentDocument(options, hit.id, hit.path);
			}
		} catch (error) {
			console.error("[StoneSync] Failed to download search result before opening", hit.path, error);
			new Notice(`StoneSync: Couldn't download "${hit.path}".`);
			return;
		}
	}

	const file = app.vault.getAbstractFileByPath(hit.path);
	if (!(file instanceof TFile)) {
		new Notice(`StoneSync: Couldn't open "${hit.path}".`);
		return;
	}
	await app.workspace.getLeaf(false).openFile(file);
}
