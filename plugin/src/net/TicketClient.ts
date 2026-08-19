import { requestUrl } from "obsidian";

export class TicketRequestError extends Error {
	constructor(message: string, readonly status?: number) {
		super(message);
		this.name = "TicketRequestError";
	}
}

/**
 * Fetches a short-lived one-time ticket for the WebSocket handshake.
 *
 * Obsidian cannot set custom headers during the WS handshake, so the API key
 * is sent exclusively here (in the REST call) as a bearer token, never via
 * the WS URL or in plaintext in proxy logs.
 *
 * `requestUrl` (instead of `fetch`) is used because it bypasses CORS and
 * works equally on desktop and mobile.
 */
export async function requestTicket(serverUrl: string, apiKey: string): Promise<string> {
	const base = serverUrl.trim().replace(/\/+$/, "");
	const response = await requestUrl({
		url: `${base}/api/auth/ticket`,
		method: "POST",
		headers: {
			Authorization: `Bearer ${apiKey}`,
			"Content-Type": "application/json",
		},
		throw: false,
	});

	if (response.status < 200 || response.status >= 300) {
		throw new TicketRequestError(
			`Ticket request failed (HTTP ${response.status}).`,
			response.status
		);
	}

	const body = response.json as { ticket?: string } | undefined;
	if (!body?.ticket) {
		throw new TicketRequestError(
			"Server response to ticket request did not contain a 'ticket' field."
		);
	}

	return body.ticket;
}
