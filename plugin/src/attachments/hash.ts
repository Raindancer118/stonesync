/**
 * SHA-256 hashing exclusively via the Web Crypto API (`crypto.subtle`).
 *
 * Deliberately NOT `node:crypto` — the plugin must run on Obsidian Mobile
 * (iOS/Android), where Node modules are not available. `crypto.subtle` is
 * present both in the Obsidian desktop renderer (Chromium) and in the mobile
 * WebView.
 */
export async function sha256Hex(data: ArrayBuffer): Promise<string> {
	const digest = await crypto.subtle.digest("SHA-256", data);
	return bufferToHex(digest);
}

function bufferToHex(buffer: ArrayBuffer): string {
	const bytes = new Uint8Array(buffer);
	let hex = "";
	for (let i = 0; i < bytes.length; i++) {
		hex += bytes[i].toString(16).padStart(2, "0");
	}
	return hex;
}
