import { requestUrl } from "obsidian";

export class TicketRequestError extends Error {
	constructor(message: string, readonly status?: number) {
		super(message);
		this.name = "TicketRequestError";
	}
}

/**
 * Holt ein kurzlebiges Einmal-Ticket für den WebSocket-Handshake.
 *
 * Obsidian kann beim WS-Handshake keine Custom-Header setzen, daher wird
 * der API-Key ausschließlich hier (im REST-Call) als Bearer-Token gesendet,
 * niemals über die WS-URL oder im Klartext in Proxy-Logs.
 *
 * `requestUrl` (statt `fetch`) wird genutzt, weil es CORS umgeht und auf
 * Desktop wie Mobile gleichermaßen funktioniert.
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
			`Ticket-Anfrage fehlgeschlagen (HTTP ${response.status}).`,
			response.status
		);
	}

	const body = response.json as { ticket?: string } | undefined;
	if (!body?.ticket) {
		throw new TicketRequestError(
			"Server-Antwort auf Ticket-Anfrage enthielt kein 'ticket'-Feld."
		);
	}

	return body.ticket;
}
