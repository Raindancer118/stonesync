import { requestUrl } from "obsidian";

export interface ExchangedApiKey {
	apiKey: string;
	vaultId: string;
	displayName: string;
}

/**
 * Trades the short-lived, single-use exchange code carried by the `stonesync-connect` deep link
 * for the actual device API key, via `POST /api/auth/exchange` (`AuthExchangeController`) - the
 * key itself never appears in the deep link URL, only this code does.
 */
export async function exchangeCode(serverUrl: string, code: string): Promise<ExchangedApiKey> {
	const base = serverUrl.trim().replace(/\/+$/, "");
	const response = await requestUrl({
		url: `${base}/api/auth/exchange`,
		method: "POST",
		headers: { "Content-Type": "application/json" },
		body: JSON.stringify({ code }),
		throw: false,
	});

	if (response.status < 200 || response.status >= 300) {
		throw new Error(`Exchange code redemption failed (HTTP ${response.status}).`);
	}

	const body = response.json as Partial<ExchangedApiKey> | undefined;
	if (!body?.apiKey || !body.vaultId || !body.displayName) {
		throw new Error("Server response was missing apiKey/vaultId/displayName.");
	}

	return { apiKey: body.apiKey, vaultId: body.vaultId, displayName: body.displayName };
}
