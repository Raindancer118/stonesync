import { requestUrl } from "obsidian";

/**
 * Tombstones a document server-side (`DELETE /api/documents/{id}`, `DocumentController#delete`),
 * so it stops existing for other devices and (per the server's DELETE_NOTICE broadcast) any
 * currently-connected session for it is told to remove the file immediately.
 */
export async function deleteDocument(serverUrl: string, apiKey: string, documentId: string): Promise<void> {
	const base = serverUrl.trim().replace(/\/+$/, "");
	const response = await requestUrl({
		url: `${base}/api/documents/${encodeURIComponent(documentId)}`,
		method: "DELETE",
		headers: {
			Authorization: `Bearer ${apiKey}`,
		},
		throw: false,
	});

	if (response.status < 200 || response.status >= 300) {
		throw new Error(`Deleting document ${documentId} failed (HTTP ${response.status}).`);
	}
}
