import { requestUrl } from "obsidian";

/**
 * Downloads the stored bytes of an attachment (`GET /api/attachments/{documentId}/download`,
 * `AttachmentController#download`). Counterpart to `AttachmentSync`'s upload path - used by
 * the bulk vault download to materialize attachment files that don't exist locally yet.
 */
export async function downloadAttachment(serverUrl: string, apiKey: string, documentId: string): Promise<ArrayBuffer> {
	const base = serverUrl.trim().replace(/\/+$/, "");
	const response = await requestUrl({
		url: `${base}/api/attachments/${encodeURIComponent(documentId)}/download`,
		method: "GET",
		headers: {
			Authorization: `Bearer ${apiKey}`,
		},
		throw: false,
	});

	if (response.status < 200 || response.status >= 300) {
		throw new Error(`Downloading attachment ${documentId} failed (HTTP ${response.status}).`);
	}

	return response.arrayBuffer;
}
