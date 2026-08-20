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
	 * Where notes from other vaults are mirrored when a cross-vault link is opened. They become
	 * ordinary notes in this vault, so everything Obsidian does with a note (links, backlinks,
	 * search, offline reading) keeps working without the server.
	 */
	mirrorFolder: string;
	/**
	 * Mirrored foreign notes: local path -> which document on the server it actually is. Without
	 * this the plugin would resolve a mirrored note against *this* vault by its path and create a
	 * duplicate there.
	 */
	mirrors: Record<string, MirroredNote>;
	/**
	 * Display name for live cursor presence. Generated randomly on first
	 * start and then persisted stably in the plugin data (instead of being
	 * re-rolled on every restart), but changeable via the settings UI.
	 */
	displayName: string;
}

export interface MirroredNote {
	documentId: string;
	vaultSlug: string;
	/** The note's path in its own vault. */
	sourcePath: string;
	writable: boolean;
}

export const DEFAULT_SETTINGS: StoneSyncSettings = {
	serverUrl: "",
	apiKey: "",
	vaultId: "",
	syncEnabled: true,
	displayName: "",
	mirrorFolder: "_shared",
	mirrors: {},
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
