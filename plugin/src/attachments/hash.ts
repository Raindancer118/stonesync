/**
 * SHA-256-Hashing ausschließlich über die Web Crypto API (`crypto.subtle`).
 *
 * Bewusst KEIN `node:crypto` — das Plugin muss auf Obsidian Mobile (iOS/Android)
 * laufen, wo Node-Module nicht verfügbar sind. `crypto.subtle` ist sowohl im
 * Obsidian-Desktop-Renderer (Chromium) als auch in der Mobile-WebView vorhanden.
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
