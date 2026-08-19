export interface StoneSyncSettings {
	/** Basis-URL des StoneSync-Servers, z.B. "https://stonesync.example.com". */
	serverUrl: string;
	/** API-Key des Nutzers, wird nur für den Ticket-Handshake genutzt (nie über WS gesendet). */
	apiKey: string;
	/** UUID des Vaults auf dem Server, mit dem synchronisiert werden soll. */
	vaultId: string;
	/** Globaler Ein/Aus-Schalter für die Synchronisation. */
	syncEnabled: boolean;
	/**
	 * Anzeigename für Live-Cursor-Presence. Wird beim ersten Start zufällig
	 * generiert und danach stabil in den Plugin-Daten persistiert (statt bei
	 * jedem Neustart neu gewürfelt zu werden), ist aber über die Settings-UI
	 * änderbar.
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

/** Leitet aus der (https/http) Server-URL die passende WebSocket-Basis-URL ab. */
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
	// keine Angabe eines Schemas: https/wss als sicheren Default annehmen
	return "wss://" + trimmed;
}

export function isConfigured(settings: StoneSyncSettings): boolean {
	return (
		settings.serverUrl.trim().length > 0 &&
		settings.apiKey.trim().length > 0 &&
		settings.vaultId.trim().length > 0
	);
}
