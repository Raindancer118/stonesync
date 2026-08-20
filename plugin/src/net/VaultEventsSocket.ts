import { ReconnectBackoff } from "./backoff";
import { type ConnectionStatus, type WebSocketLike, isAuthError } from "./StoneSyncSocket";

export interface VaultEventCreated {
	type: "document_created";
	documentId: string;
	path: string;
	contentType: "TEXT" | "ATTACHMENT";
	/** The client whose action caused this event, if any - see `net/clientSession.ts`. */
	originSessionId: string | null;
}

export interface VaultEventDeleted {
	type: "document_deleted";
	documentId: string;
	path: string;
	originSessionId: string | null;
}

/**
 * A note this user could read a moment ago is no longer theirs to see - their role changed or a
 * path rule now excludes them. Sent only to the affected user (see `VaultEventsHandler`), so the
 * local copy can be removed instead of quietly lingering on the device after access was revoked.
 */
export interface VaultEventAccessRevoked {
	type: "access_revoked";
	documentId: string | null;
	path: string;
	/** Always null here - a revocation is never something this client caused itself. */
	originSessionId: string | null;
}

export type VaultEvent = VaultEventCreated | VaultEventDeleted | VaultEventAccessRevoked;

export interface VaultEventsSocketOptions {
	/** e.g. "wss://stonesync.example.com" (no trailing slash). */
	wsBaseUrl: string;
	vaultId: string;
	/** Fetches (or renews) the short-lived one-time ticket for the handshake. */
	getTicket: () => Promise<string>;
	onEvent: (event: VaultEvent) => void;
	backoff?: ReconnectBackoff;
	/** Defaults to `(url) => new WebSocket(url)`; injectable for tests. */
	webSocketFactory?: (url: string) => WebSocketLike;
	onStatusChange?: (status: ConnectionStatus) => void;
	/** For logging/diagnostics; never thrown. */
	onError?: (error: unknown) => void;
}

/**
 * One persistent connection per vault (not per file) to the lightweight `/ws/vault/{vaultId}`
 * events channel (`VaultEventsHandler` server-side) - JSON text frames telling this client about
 * documents created/deleted anywhere in the vault, regardless of whether that document is
 * currently open. This is what makes "know in real time when a colleague deletes/adds a file"
 * possible without opening one Yjs session per file in the vault (see agy's Phase 2 scaling
 * concern - this channel is deliberately NOT that).
 */
export class VaultEventsSocket {
	private ws: WebSocketLike | null = null;
	private status: ConnectionStatus = "idle";
	private closedByUser = false;
	private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
	private readonly backoff: ReconnectBackoff;
	private readonly webSocketFactory: (url: string) => WebSocketLike;

	constructor(private readonly options: VaultEventsSocketOptions) {
		this.backoff = options.backoff ?? new ReconnectBackoff({ baseDelayMs: 1000, maxDelayMs: 30000 });
		this.webSocketFactory =
			options.webSocketFactory ?? ((url: string) => new WebSocket(url) as unknown as WebSocketLike);
	}

	getStatus(): ConnectionStatus {
		return this.status;
	}

	async connect(): Promise<void> {
		this.closedByUser = false;
		if (this.status === "connecting" || this.status === "connected") return;
		await this.attemptConnect();
	}

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

	destroy(): void {
		this.disconnect();
	}

	private async attemptConnect(): Promise<void> {
		this.setStatus(this.backoff.attempt > 0 ? "reconnecting" : "connecting");

		let ticket: string;
		try {
			ticket = await this.options.getTicket();
		} catch (error) {
			this.options.onError?.(error);
			if (isAuthError(error)) {
				this.closedByUser = true;
				this.setStatus("unauthorized");
				return;
			}
			this.scheduleReconnect();
			return;
		}

		const url = `${this.options.wsBaseUrl}/ws/vault/${encodeURIComponent(this.options.vaultId)}?ticket=${encodeURIComponent(ticket)}`;

		let ws: WebSocketLike;
		try {
			ws = this.webSocketFactory(url);
		} catch (error) {
			this.options.onError?.(error);
			this.scheduleReconnect();
			return;
		}

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
	}

	private handleClose(): void {
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

	private handleMessage(data: unknown): void {
		if (typeof data !== "string") {
			this.options.onError?.(new Error("Received a non-text vault-events message."));
			return;
		}
		let parsed: VaultEvent;
		try {
			parsed = JSON.parse(data);
		} catch (error) {
			this.options.onError?.(error);
			return;
		}
		this.options.onEvent(parsed);
	}

	private setStatus(status: ConnectionStatus): void {
		if (this.status === status) return;
		this.status = status;
		this.options.onStatusChange?.(status);
	}
}
