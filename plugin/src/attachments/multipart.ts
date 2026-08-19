export interface MultipartFields {
	/** Server-seitige Dokument-UUID (aufgelöst über DocumentIdResolver), nicht Pfad/VaultId. */
	documentId: string;
	hash: string;
	/** ISO-8601-Instant, muss zu Spring's `@DateTimeFormat(iso = DATE_TIME)` passen. */
	modifiedAt: string;
	fileName: string;
	data: ArrayBuffer;
}

/**
 * Baut einen minimalen multipart/form-data-Body ohne externe Abhängigkeiten
 * (kein `form-data`-npm-Paket, das auf Node-Streams/Buffer setzt und damit
 * auf Obsidian Mobile nicht liefe). Reine Logik, unabhängig von der
 * Obsidian-Runtime -> isoliert unit-testbar. Feldnamen müssen exakt den
 * `@RequestParam`-Namen von `AttachmentController#upload` entsprechen.
 */
export function buildMultipartBody(boundary: string, fields: MultipartFields): ArrayBuffer {
	const encoder = new TextEncoder();
	const parts: Uint8Array[] = [];

	const pushText = (text: string) => parts.push(encoder.encode(text));

	pushText(`--${boundary}\r\n`);
	pushText(`Content-Disposition: form-data; name="documentId"\r\n\r\n${fields.documentId}\r\n`);

	pushText(`--${boundary}\r\n`);
	pushText(`Content-Disposition: form-data; name="hash"\r\n\r\n${fields.hash}\r\n`);

	pushText(`--${boundary}\r\n`);
	pushText(`Content-Disposition: form-data; name="modifiedAt"\r\n\r\n${fields.modifiedAt}\r\n`);

	pushText(`--${boundary}\r\n`);
	pushText(
		`Content-Disposition: form-data; name="file"; filename="${fields.fileName}"\r\n` +
			`Content-Type: application/octet-stream\r\n\r\n`
	);
	parts.push(new Uint8Array(fields.data));
	pushText(`\r\n--${boundary}--\r\n`);

	const totalLength = parts.reduce((sum, p) => sum + p.length, 0);
	const combined = new Uint8Array(totalLength);
	let offset = 0;
	for (const part of parts) {
		combined.set(part, offset);
		offset += part.length;
	}
	return combined.buffer;
}
