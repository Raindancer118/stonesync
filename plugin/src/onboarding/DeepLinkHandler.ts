export interface ConnectParams {
	serverUrl: string;
	exchangeCode: string;
}

/**
 * Parses the query params Obsidian hands to a registered `obsidian://` protocol handler for
 * the `stonesync-connect` action (see `AuthentikLoginSuccessHandler`/`DeepLinkBuilder` on the
 * server, which build this exact URL after a successful invite login). Only a server URL and a
 * short-lived, single-use exchange code travel through the URL - the actual API key is fetched
 * separately via `ApiKeyExchangeClient` so it never sits in the browser's history. Pure and
 * Obsidian-runtime-free on purpose, so it's unit-testable without a live plugin instance -
 * Obsidian itself already URL-decodes the params before this ever runs.
 */
export function parseConnectParams(params: Record<string, string>): ConnectParams | null {
	const { serverUrl, exchangeCode } = params;
	if (!serverUrl || !exchangeCode) {
		return null;
	}
	return { serverUrl, exchangeCode };
}
