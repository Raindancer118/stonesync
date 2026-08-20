export interface ConnectParams {
	serverUrl: string;
	apiKey: string;
	vaultId: string;
	displayName: string;
}

/**
 * Parses the query params Obsidian hands to a registered `obsidian://` protocol handler for
 * the `stonesync-connect` action (see `AuthentikLoginSuccessHandler`/`DeepLinkBuilder` on the
 * server, which build this exact URL after a successful invite login). Pure and Obsidian-runtime-
 * free on purpose, so it's unit-testable without a live plugin instance - Obsidian itself already
 * URL-decodes the params before this ever runs.
 */
export function parseConnectParams(params: Record<string, string>): ConnectParams | null {
	const { serverUrl, apiKey, vaultId, displayName } = params;
	if (!serverUrl || !apiKey || !vaultId || !displayName) {
		return null;
	}
	return { serverUrl, apiKey, vaultId, displayName };
}
