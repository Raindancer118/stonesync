export interface StoneSyncSettings {
	/** Base URL of the StoneSync server, e.g. "https://stonesync.example.com". */
	serverUrl: string;
	/** API key of the user, only used for the ticket handshake (never sent over WS). */
	apiKey: string;
	/** UUID of the vault on the server to synchronize with. */
	vaultId: string;
	/** Global on/off switch for synchronization. */
	syncEnabled: boolean;
	/**
	 * Display name for live cursor presence. Generated randomly on first
	 * start and then persisted stably in the plugin data (instead of being
	 * re-rolled on every restart), but changeable via the settings UI.
	 */
	displayName: string;
}

export const DEFAULT_SETTINGS: StoneSyncSettings = {
	serverUrl: "",
	apiKey: "",
	vaultId: "",
	syncEnabled: true,
	displayName: "",
};

/** Derives the matching WebSocket base URL from the (https/http) server URL. */
export function toWebSocketBaseUrl(serverUrl: string): string {
	const trimmed = serverUrl.trim().replace(/\/+$/, "");
	if (trimmed.startsWith("https://")) {
		return "wss://" + trimmed.slice("https://".length);
	}
	if (trimmed.startsWith("http://")) {
		return "ws://" + trimmed.slice("http://".length);
	}
	if (trimmed.startsWith("wss://") || trimmed.startsWith("ws://")) {
		return trimmed;
	}
	// no scheme given: assume https/wss as a safe default
	return "wss://" + trimmed;
}

export function isConfigured(settings: StoneSyncSettings): boolean {
	return (
		settings.serverUrl.trim().length > 0 &&
		settings.apiKey.trim().length > 0 &&
		settings.vaultId.trim().length > 0
	);
}
