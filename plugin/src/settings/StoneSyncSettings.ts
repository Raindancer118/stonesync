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
	/**
	 * Snapshot of every vault-relative path this client could see on the server as of the last
	 * successful reconciliation. Compared against the current list on the next reconcile to spot
	 * documents deleted (by anyone) while this client was disconnected - see
	 * `VaultEventsManager.reconcileMissingFiles`. `undefined` until the first-ever successful
	 * reconciliation: with no prior snapshot there is nothing safe to compare against (a path
	 * missing from the very first list could just as easily be local content never synced yet).
	 */
	knownServerPaths?: string[];
	/**
	 * Vault-relative paths whose deletion couldn't be sent to the server yet (offline, or some
	 * other transient failure) - retried automatically on the next successful connect, see
	 * `SyncManager.flushPendingDeletes`. Without this, deleting a file while offline was silently
	 * final only on this device: the server never learned about it at all, not even later.
	 */
	pendingDeletePaths?: string[];
	/**
	 * Auto-opens the StoneSync home view (`StoneSyncHomeView` - branded landing tab with the
	 * server-backed search bar) once, after every Obsidian startup. Defaults to `true`: this is
	 * the "automatically set-up home page" the plugin provides out of the box; a user who'd
	 * rather land on their usual note can turn it off in Settings.
	 */
	openHomeOnStartup: boolean;
	/**
	 * In addition to `openHomeOnStartup`, also opens the StoneSync home view whenever the
	 * workspace ends up with no file open at all (e.g. the last open note gets closed). Defaults
	 * to `false`: unlike the once-per-startup case, this can fire repeatedly during a session, so
	 * it stays opt-in rather than surprising a user who deliberately closes everything.
	 */
	openHomeWhenNoFileOpen: boolean;
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
	openHomeOnStartup: true,
	openHomeWhenNoFileOpen: false,
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
