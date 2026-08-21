import { requestUrl } from "obsidian";
import type { DocumentContentType } from "../sync/DocumentIdResolver";

export interface SearchHit {
	id: string;
	path: string;
	contentType: DocumentContentType;
	snippetHtml: string;
}

/**
 * Hits the server's own Postgres full-text/fuzzy search index (`GET /api/documents/search`,
 * `DocumentController#search` - see migrations V7/V8) rather than reimplementing search
 * client-side: the same index Obsidian's built-in search doesn't have access to (it only sees
 * this device's local files), already covers PDF/OCR-extracted attachment text, typo tolerance,
 * and per-path access scoping.
 */
export async function searchVault(
	serverUrl: string,
	apiKey: string,
	vaultId: string,
	query: string
): Promise<SearchHit[]> {
	if (query.trim().length === 0) return [];

	const base = serverUrl.trim().replace(/\/+$/, "");
	const response = await requestUrl({
		url: `${base}/api/documents/search?vaultId=${encodeURIComponent(vaultId)}&q=${encodeURIComponent(query)}`,
		method: "GET",
		headers: {
			Authorization: `Bearer ${apiKey}`,
		},
		throw: false,
	});

	if (response.status < 200 || response.status >= 300) {
		throw new Error(`Searching vault "${vaultId}" failed (HTTP ${response.status}).`);
	}

	const body = response.json as SearchHit[] | undefined;
	if (!Array.isArray(body)) {
		throw new Error(`Server response for search was not an array for vault "${vaultId}".`);
	}
	return body;
}
