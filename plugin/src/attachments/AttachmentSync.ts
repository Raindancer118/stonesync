import { requestUrl, type DataAdapter } from "obsidian";
import { sha256Hex } from "./hash";
import { buildMultipartBody } from "./multipart";
import { DocumentIdResolver } from "../sync/DocumentIdResolver";

export interface AttachmentSyncOptions {
	serverUrl: string;
	apiKey: string;
	vaultId: string;
	adapter: DataAdapter;
	/** Injizierbar für Tests; produktiv wird intern ein eigener Resolver erzeugt. */
	documentIdResolver?: DocumentIdResolver;
}

export interface AttachmentUploadResult {
	uploaded: boolean;
	hash: string;
}

/**
 * Anhang-Synchronisation, getrennt vom Yjs/CRDT-Kanal (kein CRDT für
 * Binärdateien). Ablauf: SHA-256-Hash lokal berechnen, Server fragen ob der
 * Hash bereits bekannt ist, nur bei unbekanntem Hash tatsächlich hochladen.
 *
 * Ausschließlich `app.vault.adapter` für Dateizugriff (kein Node `fs`/`path`),
 * damit das Plugin auch auf Obsidian Mobile funktioniert.
 */
export class AttachmentSync {
	private readonly documentIdResolver: DocumentIdResolver;

	constructor(private readonly options: AttachmentSyncOptions) {
		this.documentIdResolver =
			options.documentIdResolver ?? new DocumentIdResolver(options.serverUrl, options.apiKey, options.vaultId);
	}

	/** Prüft den Hash gegen den Server und lädt die Datei nur bei Bedarf hoch. */
	async syncFile(vaultRelativePath: string): Promise<AttachmentUploadResult> {
		const data = await this.options.adapter.readBinary(vaultRelativePath);
		const hash = await sha256Hex(data);

		const known = await this.checkHashKnown(hash);
		if (known) {
			return { uploaded: false, hash };
		}

		const documentId = await this.documentIdResolver.resolve(vaultRelativePath, "ATTACHMENT");
		await this.upload(vaultRelativePath, documentId, hash, data);
		return { uploaded: true, hash };
	}

	private async checkHashKnown(hash: string): Promise<boolean> {
		const base = this.options.serverUrl.trim().replace(/\/+$/, "");
		const response = await requestUrl({
			url: `${base}/api/attachments/status?hash=${encodeURIComponent(hash)}`,
			method: "GET",
			headers: {
				Authorization: `Bearer ${this.options.apiKey}`,
			},
			throw: false,
		});

		if (response.status === 404) return false;
		if (response.status < 200 || response.status >= 300) {
			throw new Error(`Attachment-Status-Check fehlgeschlagen (HTTP ${response.status}).`);
		}

		const body = response.json as { known?: boolean } | undefined;
		return body?.known === true;
	}

	private async upload(vaultRelativePath: string, documentId: string, hash: string, data: ArrayBuffer): Promise<void> {
		const base = this.options.serverUrl.trim().replace(/\/+$/, "");
		const boundary = `StoneSyncBoundary${Math.random().toString(16).slice(2)}`;
		const fileName = vaultRelativePath.split("/").pop() ?? vaultRelativePath;

		const body = buildMultipartBody(boundary, {
			documentId,
			hash,
			modifiedAt: new Date().toISOString(),
			fileName,
			data,
		});

		const response = await requestUrl({
			url: `${base}/api/attachments/upload`,
			method: "POST",
			headers: {
				Authorization: `Bearer ${this.options.apiKey}`,
				"Content-Type": `multipart/form-data; boundary=${boundary}`,
			},
			body,
			throw: false,
		});

		if (response.status < 200 || response.status >= 300) {
			throw new Error(`Attachment-Upload fehlgeschlagen (HTTP ${response.status}).`);
		}
	}
}

