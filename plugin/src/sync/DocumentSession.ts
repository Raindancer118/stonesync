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
			onStatusChange: options.onStatusChange,
			onError: options.onError,
			onCaughtUp: () => {
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
		if (this.materializeTimer) {
			clearTimeout(this.materializeTimer);
		}
		this.materializeTimer = setTimeout(() => {
			this.materializeTimer = null;
			materializeDocument(this.options.serverUrl, this.options.apiKey, this.options.documentId,
				this.ytext.toString()
			).catch((error) => console.error("[StoneSync] Failed to materialize document", this.options.documentId, error));
		}, MATERIALIZE_DEBOUNCE_MS);
	}

	/**
	 * Resolves the next time the server's on-connect history replay burst finishes (see
	 * `StoneSyncSocketOptions.onCaughtUp`). Intended for one-shot flows (e.g. a bulk vault
	 * download) that need to know when a freshly connected, empty local doc has received all
	 * existing content - not a general "is it caught up right now" query.
	 */
	waitUntilCaughtUp(): Promise<void> {
		return new Promise((resolve) => {
			this.caughtUpResolvers.push(resolve);
		});
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

	destroy(): void {
		if (this.materializeTimer) {
			clearTimeout(this.materializeTimer);
			this.materializeTimer = null;
		}
		this.socket.destroy();
		this.awareness.destroy();
		this.doc.destroy();
		this.connected = false;
	}
}
