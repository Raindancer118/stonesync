import { requestUrl } from "obsidian";
import { getClientSessionId } from "../net/clientSession";

/**
 * Resolves the server-side document UUID (`documents.id`) for a
 * vault-relative path. According to the data model, paths are never the
 * primary key (`current_path` is only a metadata field) — renames on the
 * client side do not change the documentId, which is why caching happens
 * locally here.
 *
 * Server endpoint: `POST /api/documents/resolve` (`DocumentController#resolve`)
 * — for a known (vaultId, path) returns the existing UUID; for an unknown
 * path creates a new `documents` row and returns its fresh UUID.
 */
export type DocumentContentType = "TEXT" | "ATTACHMENT";

export class DocumentIdResolver {
	private readonly cache = new Map<string, string>();

	constructor(
		private readonly serverUrl: string,
		private readonly apiKey: string,
		private readonly vaultId: string
	) {}

	async resolve(vaultRelativePath: string, contentType: DocumentContentType = "TEXT"): Promise<string> {
		const cached = this.cache.get(vaultRelativePath);
		if (cached) return cached;

		const base = this.serverUrl.trim().replace(/\/+$/, "");
		const response = await requestUrl({
			url: `${base}/api/documents/resolve`,
			method: "POST",
			headers: {
				Authorization: `Bearer ${this.apiKey}`,
				"Content-Type": "application/json",
				"X-StoneSync-Session": getClientSessionId(),
			},
			body: JSON.stringify({ vaultId: this.vaultId, path: vaultRelativePath, contentType }),
			throw: false,
		});

		if (response.status < 200 || response.status >= 300) {
			throw new Error(`Document resolution failed (HTTP ${response.status}) for "${vaultRelativePath}".`);
		}

		const body = response.json as { documentId?: string } | undefined;
		if (!body?.documentId) {
			throw new Error(`Server response did not contain a documentId for "${vaultRelativePath}".`);
		}

		this.cache.set(vaultRelativePath, body.documentId);
		return body.documentId;
	}

	/** Returns the cached documentId for a path, if one has been resolved before, without a network call. */
	peekId(vaultRelativePath: string): string | undefined {
		return this.cache.get(vaultRelativePath);
	}

	/** On client-side rename: move the cache key along, so no new resolve call is needed. */
	rename(oldPath: string, newPath: string): void {
		const id = this.cache.get(oldPath);
		if (id) {
			this.cache.delete(oldPath);
			this.cache.set(newPath, id);
		}
	}

	forget(path: string): void {
		this.cache.delete(path);
	}
}
