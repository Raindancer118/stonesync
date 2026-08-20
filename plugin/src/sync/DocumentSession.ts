import * as Y from "yjs";
import { Awareness } from "y-protocols/awareness";
import { StoneSyncSocket, ConnectionStatus } from "../net/StoneSyncSocket";
import { requestTicket } from "../net/TicketClient";
import { toWebSocketBaseUrl } from "../settings/StoneSyncSettings";
import { materializeDocument } from "./MaterializeClient";

/** How long to wait after the last edit before pushing a materialize snapshot (see `scheduleMaterialize`). */
const MATERIALIZE_DEBOUNCE_MS = 3000;

export interface DocumentSessionOptions {
	documentId: string;
	serverUrl: string;
	apiKey: string;
	userName: string;
	userColor: string;
	onStatusChange?: (status: ConnectionStatus) => void;
	onError?: (error: unknown) => void;
	/** See `StoneSyncSocketOptions.onCaughtUp`. */
	onCaughtUp?: () => void;
	/** See `StoneSyncSocketOptions.onRestoreContent`. */
	onRestoreContent?: () => void;
	/** See `StoneSyncSocketOptions.onDeleteNotice`. */
	onDeleteNotice?: () => void;
	/**
	 * When true, this session never pushes content to the git side-channel. The server refuses
	 * such writes anyway (see `MaterializeService`), so sending them would only produce a stream
	 * of 403s and audit noise for someone who is simply reading.
	 */
	readOnly?: boolean;
}

/**
 * One sync session per text file: its own Y.Doc (with a "content" Y.Text)
 * + its own awareness instance + its own WebSocket connection (the server
 * handshake is per document, see `wss://.../ws?ticket=...&documentId=<UUID>`).
 *
 * Design decision (instead of a single shared Y.Doc with one Y.Text per file
 * for the whole vault): the wire protocol explicitly binds the connection to
 * exactly one `documentId`, and live cursor presence is only relevant among
 * users who have the same file open anyway. A Y.Doc per file keeps the blast
 * radius small (a parse/encoding error affects only one document), avoids
 * unnecessary network load for files that aren't open, and mirrors the
 * server-side data model (`documents` table, one row per file) 1:1.
 */
export class DocumentSession {
	readonly doc = new Y.Doc();
	readonly ytext = this.doc.getText("content");
	readonly awareness = new Awareness(this.doc);
	private readonly socket: StoneSyncSocket;
	private connected = false;
	private caughtUpResolvers: Array<() => void> = [];
	private materializeTimer: ReturnType<typeof setTimeout> | null = null;
	/** Whether the server's history replay for the *current* connection has already finished. */
	private caughtUp = false;

	constructor(private readonly options: DocumentSessionOptions) {
		this.awareness.setLocalStateField("user", {
			name: options.userName,
			color: options.userColor,
		});

		this.socket = new StoneSyncSocket({
			wsBaseUrl: toWebSocketBaseUrl(options.serverUrl),
			documentId: options.documentId,
			getTicket: () => requestTicket(options.serverUrl, options.apiKey),
			doc: this.doc,
			awareness: this.awareness,
			onStatusChange: (status) => {
				// A new (or re-established) connection replays history again, so "caught up"
				// only ever describes the connection that is currently up.
				if (status === "connecting" || status === "reconnecting") {
					this.caughtUp = false;
				}
				options.onStatusChange?.(status);
			},
			onError: options.onError,
			onCaughtUp: () => {
				this.caughtUp = true;
				options.onCaughtUp?.();
				const resolvers = this.caughtUpResolvers;
				this.caughtUpResolvers = [];
				resolvers.forEach((resolve) => resolve());
			},
			onRestoreContent: options.onRestoreContent,
			onDeleteNotice: options.onDeleteNotice,
		});

		// Materialize side-channel: purely for git-backed history/durability, entirely decoupled
		// from the Yjs sync path above - fires on every content change regardless of origin
		// (local edit or an applied remote update), debounced so a burst of keystrokes produces
		// one commit, not one per keystroke.
		this.ytext.observe(() => this.scheduleMaterialize());
	}

	private scheduleMaterialize(): void {
		if (this.options.readOnly) return;
		if (this.materializeTimer) {
			clearTimeout(this.materializeTimer);
		}
		this.materializeTimer = setTimeout(() => {
			this.materializeTimer = null;
			this.flushMaterialize();
		}, MATERIALIZE_DEBOUNCE_MS);
	}

	private flushMaterialize(): void {
		materializeDocument(this.options.serverUrl, this.options.apiKey, this.options.documentId, this.ytext.toString())
			.catch((error) => console.error("[StoneSync] Failed to materialize document", this.options.documentId, error));
	}

	/**
	 * Resolves the next time the server's on-connect history replay burst finishes (see
	 * `StoneSyncSocketOptions.onCaughtUp`). Intended for one-shot flows (e.g. a bulk vault
	 * download) that need to know when a freshly connected, empty local doc has received all
	 * existing content - not a general "is it caught up right now" query.
	 */
	waitUntilCaughtUp(): Promise<void> {
		if (this.caughtUp) return Promise.resolve();
		return new Promise((resolve) => {
			this.caughtUpResolvers.push(resolve);
		});
	}

	/**
	 * Connects (if not connected yet) and resolves once the server's replay burst is done -
	 * or after `timeoutMs`, so an offline/unreachable server degrades to "bind the editor
	 * anyway and let the socket reconnect in the background" instead of never binding at all.
	 */
	async connectAndWaitUntilCaughtUp(timeoutMs: number): Promise<boolean> {
		const caughtUp = this.waitUntilCaughtUp();
		await this.connect();
		let timer: ReturnType<typeof setTimeout> | null = null;
		const timeout = new Promise<false>((resolve) => {
			timer = setTimeout(() => resolve(false), timeoutMs);
		});
		try {
			return await Promise.race([caughtUp.then(() => true), timeout]);
		} finally {
			if (timer) clearTimeout(timer);
		}
	}

	/**
	 * Other users currently present in this document (from the Yjs awareness protocol), i.e.
	 * everyone whose cursor is rendered in the editor right now. Drives the "N others editing"
	 * indicator; the local client is always excluded.
	 */
	peers(): Array<{ name: string; color: string }> {
		const peers: Array<{ name: string; color: string }> = [];
		this.awareness.getStates().forEach((state, clientId) => {
			if (clientId === this.awareness.clientID) return;
			const user = (state as { user?: { name?: string; color?: string } }).user;
			if (!user?.name) return;
			peers.push({ name: user.name, color: user.color ?? "#888888" });
		});
		return peers;
	}

	/** Notifies whenever the set of present collaborators changes (join/leave/cursor move). */
	onPeersChange(listener: () => void): () => void {
		this.awareness.on("change", listener);
		return () => this.awareness.off("change", listener);
	}

	get documentId(): string {
		return this.options.documentId;
	}

	async connect(): Promise<void> {
		if (this.connected) return;
		this.connected = true;
		await this.socket.connect();
	}

	getStatus(): ConnectionStatus {
		return this.socket.getStatus();
	}

	/**
	 * Seeds a fresh, empty Y.Text with the current local file content (only
	 * relevant when this file is being synchronized for the very first time
	 * and no remote history exists yet).
	 */
	seedIfEmpty(localContent: string): void {
		if (this.ytext.length > 0 || localContent.length === 0) return;
		this.doc.transact(() => {
			this.ytext.insert(0, localContent);
		});
	}

	/**
	 * Tearing a session down (e.g. switching away from this file within the 3s materialize
	 * debounce window) must not silently drop the last edit from git history - so any pending
	 * debounce is flushed immediately instead of just cancelled (found via agy architecture
	 * review: the Yjs WebSocket already persisted the edit by the time this runs, but the
	 * separate git side-channel would otherwise lag behind by up to 3s forever).
	 */
	destroy(): void {
		if (this.options.readOnly) {
			this.awareness.setLocalState(null);
			this.socket.destroy();
			this.awareness.destroy();
			this.doc.destroy();
			this.connected = false;
			return;
		}
		if (this.materializeTimer) {
			clearTimeout(this.materializeTimer);
			this.materializeTimer = null;
			this.flushMaterialize();
		}
		// Announce our departure BEFORE the socket goes away: clearing the local awareness state
		// emits an update that the still-open socket relays, so collaborators see the cursor
		// disappear at once. Tearing the socket down first (the previous order) swallowed that
		// frame and left a ghost cursor around until the 30s awareness timeout expired.
		this.awareness.setLocalState(null);
		this.socket.destroy();
		this.awareness.destroy();
		this.doc.destroy();
		this.connected = false;
	}
}
