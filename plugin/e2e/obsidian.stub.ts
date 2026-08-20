/**
 * Minimal stand-in for the parts of the Obsidian API that the networking layer touches, so the
 * real client code (TicketClient, DocumentIdResolver, DocumentSession, StoneSyncSocket) can be
 * exercised against a real server from plain Node. Only `requestUrl` is needed - it is
 * Obsidian's CORS-free fetch wrapper.
 */
export async function requestUrl(options: {
	url: string;
	method?: string;
	headers?: Record<string, string>;
	body?: string;
	throw?: boolean;
}): Promise<{ status: number; json: unknown; text: string; arrayBuffer: ArrayBuffer }> {
	const response = await fetch(options.url, {
		method: options.method ?? "GET",
		headers: options.headers,
		body: options.body,
	});
	const buffer = await response.arrayBuffer();
	const text = new TextDecoder().decode(buffer);
	let json: unknown = undefined;
	try {
		json = JSON.parse(text);
	} catch {
		/* not JSON - mirrors Obsidian's lenient behavior */
	}
	return { status: response.status, json, text, arrayBuffer: buffer };
}

export class Notice {
	constructor(readonly message: string) {}
}
