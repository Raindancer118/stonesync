import { requestUrl } from "obsidian";

/**
 * Hands the server a decoded plaintext snapshot of a document, purely as a durability/history
 * side-channel (`POST /api/documents/{id}/materialize`, `MaterializeController`) - completely
 * decoupled from the Yjs sync WebSocket. The server is deliberately never told how to decode
 * CRDT bytes; this is the one place the client voluntarily hands over already-decoded content.
 */
export async function materializeDocument(serverUrl: string, apiKey: string, documentId: string,
	content: string): Promise<void> {
	const base = serverUrl.trim().replace(/\/+$/, "");
	const response = await requestUrl({
		url: `${base}/api/documents/${encodeURIComponent(documentId)}/materialize`,
		method: "POST",
		headers: {
			Authorization: `Bearer ${apiKey}`,
			"Content-Type": "text/plain",
		},
		body: content,
		throw: false,
	});

	if (response.status < 200 || response.status >= 300) {
		throw new Error(`Materializing document ${documentId} failed (HTTP ${response.status}).`);
	}
}
