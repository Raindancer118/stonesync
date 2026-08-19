import { requestUrl } from "obsidian";

/**
 * Löst die Server-seitige Dokument-UUID (`documents.id`) für einen
 * Vault-relativen Pfad auf. Pfade sind laut Datenmodell niemals Primary
 * Key (`current_path` ist nur Metadatenfeld) — Renames auf Client-Seite
 * ändern nicht die documentId, weshalb hier lokal gecacht wird.
 *
 * Server-Endpoint: `POST /api/documents/resolve` (`DocumentController#resolve`)
 * — liefert für ein bekanntes (vaultId, path) die bestehende UUID zurück,
 * legt bei unbekanntem Pfad ein neues `documents`-Row an und liefert dessen
 * frische UUID.
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
			},
			body: JSON.stringify({ vaultId: this.vaultId, path: vaultRelativePath, contentType }),
			throw: false,
		});

		if (response.status < 200 || response.status >= 300) {
			throw new Error(`Dokument-Auflösung fehlgeschlagen (HTTP ${response.status}) für "${vaultRelativePath}".`);
		}

		const body = response.json as { documentId?: string } | undefined;
		if (!body?.documentId) {
			throw new Error(`Server-Antwort enthielt keine documentId für "${vaultRelativePath}".`);
		}

		this.cache.set(vaultRelativePath, body.documentId);
		return body.documentId;
	}

	/** Bei Rename auf Client-Seite: Cache-Key mitziehen, damit kein neuer Resolve-Call nötig ist. */
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
