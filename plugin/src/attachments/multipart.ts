export interface MultipartFields {
	/** Server-side document UUID (resolved via DocumentIdResolver), not path/vaultId. */
	documentId: string;
	hash: string;
	/** ISO-8601 instant, must match Spring's `@DateTimeFormat(iso = DATE_TIME)`. */
	modifiedAt: string;
	fileName: string;
	data: ArrayBuffer;
}

/**
 * Builds a minimal multipart/form-data body without external dependencies
 * (no `form-data` npm package, which relies on Node streams/Buffer and thus
 * would not run on Obsidian Mobile). Pure logic, independent of the Obsidian
 * runtime -> unit-testable in isolation. Field names must exactly match the
 * `@RequestParam` names of `AttachmentController#upload`.
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
