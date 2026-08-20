import { requestUrl } from "obsidian";
import type { DocumentContentType } from "./DocumentIdResolver";

export interface DocumentSummary {
	id: string;
	path: string;
	contentType: DocumentContentType;
}

/**
 * Lists every non-deleted document of a vault (`GET /api/documents?vaultId=...`,
 * `DocumentController#list`) - the basis for the bulk vault download: before anything can be
 * downloaded, the client needs to know the full set of (id, path, contentType) tuples that
 * currently exist server-side.
 */
export async function listDocuments(
	serverUrl: string,
	apiKey: string,
	vaultId: string
): Promise<DocumentSummary[]> {
	const base = serverUrl.trim().replace(/\/+$/, "");
	const response = await requestUrl({
		url: `${base}/api/documents?vaultId=${encodeURIComponent(vaultId)}`,
		method: "GET",
		headers: {
			Authorization: `Bearer ${apiKey}`,
		},
		throw: false,
	});

	if (response.status < 200 || response.status >= 300) {
		throw new Error(`Listing documents for vault "${vaultId}" failed (HTTP ${response.status}).`);
	}

	const body = response.json as DocumentSummary[] | undefined;
	if (!Array.isArray(body)) {
		throw new Error(`Server response for document listing was not an array for vault "${vaultId}".`);
	}
	return body;
}
