import * as Y from "yjs";
import { Awareness, applyAwarenessUpdate, encodeAwarenessUpdate } from "y-protocols/awareness";
import { MessageType, encodeMessage, decodeMessage, StoneSyncProtocolError } from "../protocol/prefix";
import { ReconnectBackoff } from "./backoff";
import { computeOfflineDiff, isEmptyUpdate } from "../sync/stateVectorSync";

/**
 * "unauthorized" is terminal, like "closed", but distinguishes an invalid/revoked API key
 * (nothing will fix itself by retrying) from a transient network drop (worth reconnecting).
 */
export type ConnectionStatus = "idle" | "connecting" | "connected" | "reconnecting" | "closed" | "unauthorized";

/**
 * Minimal subset of the WebSocket API that this client needs.
 * Allows injecting an in-memory fake implementation in tests, without
 * needing real network sockets or the Obsidian runtime.
 */
export interface WebSocketLike {
	readyState: number;
	binaryType: string;
	onopen: (() => void) | null;
	onclose: (() => void) | null;
	onerror: ((err: unknown) => void) | null;
	onmessage: ((event: { data: unknown }) => void) | null;
	send(data: ArrayBufferLike | ArrayBufferView | string): void;
	close(): void;
}

const WS_OPEN = 1;

export interface StoneSyncSocketOptions {
	/** e.g. "wss://stonesync.example.com" (no trailing slash). */
	wsBaseUrl: string;
	/** UUID of the document this connection synchronizes. */
	documentId: string;
	/** Fetches (or renews) the short-lived one-time ticket for the handshake. */
	getTicket: () => Promise<string>;
	doc: Y.Doc;
	awareness: Awareness;
	backoff?: ReconnectBackoff;
	/** Defaults to `(url) => new WebSocket(url)`; injectable for tests. */
	webSocketFactory?: (url: string) => WebSocketLike;
	onStatusChange?: (status: ConnectionStatus) => void;
	/** For logging/diagnostics; never thrown. */
	onError?: (error: unknown) => void;
	/**
	 * Fired once after (re)connecting, when the server's on-connect history replay burst is
	 * done (existing snapshot + update log, if any, have already been applied via the normal
	 * DocUpdate/Y.applyUpdate path). Never fired more than once per connection - it marks "no
	 * more historical replay is coming", not an ongoing sync-state flag.
	 */
	onCaughtUp?: () => void;
	/**
	 * Fired after a RESTORE_CONTENT frame has been applied locally (see `handleMessage`) - purely
	 * informational (e.g. a Notice), since the content replace itself already happened.
	 */
	onRestoreContent?: () => void;
	/**
	 * Fired when the server reports this document was deleted elsewhere. The caller is
	 * responsible for removing the local file and tearing this session down - this class does
	 * not do either itself, since it has no knowledge of the local filesystem.
	 */
	onDeleteNotice?: () => void;
}

/**
 * Custom WebSocket client for the StoneSync protocol.
 *
 * Deliberately NOT a 1:1 use of `y-websocket`, because:
 *  - the auth flow is custom (ticket handshake instead of header/query token),
 *  - the server is "dumb" (a pure blob relay, no sync-step-1/2 protocol),
 *  - reconnect behavior and offline delta resync must be tailored exactly to
 *    the StoneSync server protocol (prefix byte, REQUEST_SNAPSHOT).
 */
export class StoneSyncSocket {
	private ws: WebSocketLike | null = null;
	private status: ConnectionStatus = "idle";
	private closedByUser = false;
	private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
	private readonly backoff: ReconnectBackoff;
	private readonly webSocketFactory: (url: string) => WebSocketLike;
	/** State of the doc immediately before the last connection loss (for delta resync). */
	private preDisconnectStateVector: Uint8Array | null = null;
	private readonly onDocUpdate = (update: Uint8Array, origin: unknown) => {
		if (origin === this) return; // remote update we applied ourselves, don't send it back
		this.sendFrame(MessageType.DocUpdate, update);
	};
	private readonly onAwarenessUpdate = (
		{ added, updated, removed }: { added: number[]; updated: number[]; removed: number[] },
		origin: unknown
	) => {
		if (origin === this) return;
		const changedClients = added.concat(updated, removed);
		if (changedClients.length === 0) return;
		const update = encodeAwarenessUpdate(this.options.awareness, changedClients);
		this.sendFrame(MessageType.AwarenessUpdate, update);
	};

	constructor(private readonly options: StoneSyncSocketOptions) {
		this.backoff = options.backoff ?? new ReconnectBackoff({ baseDelayMs: 1000, maxDelayMs: 30000 });
		this.webSocketFactory =
			options.webSocketFactory ?? ((url: string) => new WebSocket(url) as unknown as WebSocketLike);

		this.options.doc.on("update", this.onDocUpdate);
		this.options.awareness.on("update", this.onAwarenessUpdate);
	}

	getStatus(): ConnectionStatus {
		return this.status;
	}

	/** Establishes the connection (or triggers the first reconnect attempt). */
	async connect(): Promise<void> {
		this.closedByUser = false;
		if (this.status === "connecting" || this.status === "connected") return;
		await this.attemptConnect();
	}

	/** Disconnects permanently; no automatic reconnect will happen afterward. */
	disconnect(): void {
		this.closedByUser = true;
		if (this.reconnectTimer) {
			clearTimeout(this.reconnectTimer);
			this.reconnectTimer = null;
		}
		this.ws?.close();
		this.ws = null;
		this.setStatus("closed");
	}

	/** Detaches all listeners; this instance is no longer usable afterward. */
	destroy(): void {
		this.disconnect();
		this.options.doc.off("update", this.onDocUpdate);
		this.options.awareness.off("update", this.onAwarenessUpdate);
	}

	private async attemptConnect(): Promise<void> {
		this.setStatus(this.backoff.attempt > 0 ? "reconnecting" : "connecting");

		let ticket: string;
		try {
			ticket = await this.options.getTicket();
		} catch (error) {
			this.options.onError?.(error);
			if (isAuthError(error)) {
				// Invalid/revoked API key: retrying won't help and would otherwise hammer the
				// server forever with exponential backoff. Stop for good and let the caller
				// (e.g. an Obsidian Notice) tell the user to fix their settings.
				this.closedByUser = true;
				this.setStatus("unauthorized");
				return;
			}
			this.scheduleReconnect();
			return;
		}

		const url = `${this.options.wsBaseUrl}/ws/sync/${encodeURIComponent(this.options.documentId)}?ticket=${encodeURIComponent(ticket)}`;

		let ws: WebSocketLike;
		try {
			ws = this.webSocketFactory(url);
		} catch (error) {
			this.options.onError?.(error);
			this.scheduleReconnect();
			return;
		}

		ws.binaryType = "arraybuffer";
		ws.onopen = () => this.handleOpen();
		ws.onclose = () => this.handleClose();
		ws.onerror = (err) => {
			this.options.onError?.(err);
		};
		ws.onmessage = (event) => this.handleMessage(event.data);

		this.ws = ws;
	}

	private handleOpen(): void {
		this.backoff.reset();
		this.setStatus("connected");
		this.performResync();
	}

	private handleClose(): void {
		this.preDisconnectStateVector = Y.encodeStateVector(this.options.doc);
		this.ws = null;
		if (this.closedByUser) {
			this.setStatus("closed");
			return;
		}
		this.scheduleReconnect();
	}

	private scheduleReconnect(): void {
		if (this.closedByUser) return;
		this.setStatus("reconnecting");
		const delay = this.backoff.nextDelayMs();
		this.reconnectTimer = setTimeout(() => {
			this.reconnectTimer = null;
			void this.attemptConnect();
		}, delay);
	}

	/**
	 * After a (re)connect, sends only the delta since the last known state
	 * ("delta resync"), instead of reloading the entire document. On a very
	 * first connect, the baseline is the empty state vector, so the full
	 * existing local content (if any) is sent once.
	 */
	private performResync(): void {
		const baseline = this.preDisconnectStateVector ?? Y.encodeStateVector(new Y.Doc());
		const diff = computeOfflineDiff(this.options.doc, baseline);
		this.preDisconnectStateVector = null;
		if (!isEmptyUpdate(diff)) {
			this.sendFrame(MessageType.DocUpdate, diff);
		}

		// re-announce our own awareness state after every (re)connect
		const localState = this.options.awareness.getLocalState();
		if (localState !== null) {
			const update = encodeAwarenessUpdate(this.options.awareness, [this.options.awareness.clientID]);
			this.sendFrame(MessageType.AwarenessUpdate, update);
		}
	}

	private handleMessage(data: unknown): void {
		const bytes = toUint8Array(data);
		if (!bytes) {
			this.options.onError?.(new Error("Received unknown WebSocket message format."));
			return;
		}

		let decoded;
		try {
			decoded = decodeMessage(bytes);
		} catch (error) {
			if (error instanceof StoneSyncProtocolError) {
				this.options.onError?.(error);
				return;
			}
			throw error;
		}

		if (decoded.type === MessageType.DocUpdate) {
			Y.applyUpdate(this.options.doc, decoded.payload, this);
		} else if (decoded.type === MessageType.AwarenessUpdate) {
			applyAwarenessUpdate(this.options.awareness, decoded.payload, this);
		} else if (decoded.type === MessageType.RequestSnapshot) {
			this.replyWithSnapshot();
		} else if (decoded.type === MessageType.CaughtUp) {
			this.options.onCaughtUp?.();
		} else if (decoded.type === MessageType.RestoreContent) {
			this.applyRestoreContent(decoded.payload);
		} else if (decoded.type === MessageType.DeleteNotice) {
			this.options.onDeleteNotice?.();
		}
		// SnapshotPayload is never sent back to clients by the server (client->server only).
	}

	/**
	 * Applies a git-restore point-in-time: replaces the entire "content" Y.Text in one
	 * transaction (the same delete-all + insert idiom `DocumentSession.seedIfEmpty` already uses
	 * for initial content). Deliberately does NOT use `this` as the transaction origin - unlike a
	 * remote DocUpdate, this needs to flow back out through the normal `onDocUpdate` listener as
	 * an ordinary 0x00 frame, so the server actually persists the restore into the document's
	 * Yjs update log (otherwise a brand new device connecting later would never see it via the
	 * regular catch-up replay).
	 */
	private applyRestoreContent(payload: Uint8Array): void {
		const content = new TextDecoder().decode(payload);
		const ytext = this.options.doc.getText("content");
		this.options.doc.transact(() => {
			ytext.delete(0, ytext.length);
			ytext.insert(0, content);
		});
		this.options.onRestoreContent?.();
	}

	/** Replies to a REQUEST_SNAPSHOT server message with the full document state. */
	private replyWithSnapshot(): void {
		const snapshot = Y.encodeStateAsUpdate(this.options.doc);
		this.sendFrame(MessageType.SnapshotPayload, snapshot);
	}

	private sendFrame(type: MessageType, payload: Uint8Array): void {
		if (!this.ws || this.ws.readyState !== WS_OPEN) return;
		this.ws.send(encodeMessage(type, payload));
	}

	private setStatus(status: ConnectionStatus): void {
		if (this.status === status) return;
		this.status = status;
		this.options.onStatusChange?.(status);
	}
}

/**
 * Duck-types an auth failure out of whatever `getTicket()` throws, without this module having
 * to depend on `TicketRequestError` from `TicketClient.ts` (which imports the Obsidian API and
 * therefore isn't safely importable in a plain unit-test context).
 */
export function isAuthError(error: unknown): boolean {
	if (typeof error !== "object" || error === null || !("status" in error)) return false;
	const status = (error as { status?: unknown }).status;
	return status === 401 || status === 403;
}

function toUint8Array(data: unknown): Uint8Array | null {
	if (data instanceof Uint8Array) return data;
	if (data instanceof ArrayBuffer) return new Uint8Array(data);
	return null;
}
