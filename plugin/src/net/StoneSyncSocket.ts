import * as Y from "yjs";
import { Awareness, applyAwarenessUpdate, encodeAwarenessUpdate } from "y-protocols/awareness";
import { MessageType, encodeMessage, decodeMessage, StoneSyncProtocolError } from "../protocol/prefix";
import { ReconnectBackoff } from "./backoff";
import { computeOfflineDiff, isEmptyUpdate } from "../sync/stateVectorSync";

export type ConnectionStatus = "idle" | "connecting" | "connected" | "reconnecting" | "closed";

/**
 * Minimale Teilmenge der WebSocket-API, die dieser Client benötigt.
 * Erlaubt es, in Tests eine In-Memory-Fake-Implementierung zu injizieren,
 * ohne echte Netzwerk-Sockets oder die Obsidian-Runtime zu benötigen.
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
	/** z.B. "wss://stonesync.example.com" (kein trailing slash). */
	wsBaseUrl: string;
	/** UUID des Dokuments, mit dem diese Verbindung synchronisiert. */
	documentId: string;
	/** Holt (bzw. erneuert) das kurzlebige Einmal-Ticket für den Handshake. */
	getTicket: () => Promise<string>;
	doc: Y.Doc;
	awareness: Awareness;
	backoff?: ReconnectBackoff;
	/** Standardmäßig `(url) => new WebSocket(url)`; für Tests injizierbar. */
	webSocketFactory?: (url: string) => WebSocketLike;
	onStatusChange?: (status: ConnectionStatus) => void;
	/** Für Logging/Diagnose; wird nie geworfen. */
	onError?: (error: unknown) => void;
}

/**
 * Eigener WebSocket-Client für das StoneSync-Protokoll.
 *
 * Bewusst KEIN 1:1-Einsatz von `y-websocket`, da:
 *  - der Auth-Flow custom ist (Ticket-Handshake statt Header/Query-Token),
 *  - der Server "dumm" ist (reines Blob-Relay, kein Sync-Step-1/2-Protokoll),
 *  - Reconnect-Verhalten und Offline-Delta-Resync exakt auf das
 *    StoneSync-Serverprotokoll (Prefix-Byte, REQUEST_SNAPSHOT) zugeschnitten
 *    sein müssen.
 */
export class StoneSyncSocket {
	private ws: WebSocketLike | null = null;
	private status: ConnectionStatus = "idle";
	private closedByUser = false;
	private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
	private readonly backoff: ReconnectBackoff;
	private readonly webSocketFactory: (url: string) => WebSocketLike;
	/** Zustand des Docs unmittelbar vor dem letzten Verbindungsabbruch (für Delta-Resync). */
	private preDisconnectStateVector: Uint8Array | null = null;
	private readonly onDocUpdate = (update: Uint8Array, origin: unknown) => {
		if (origin === this) return; // von uns selbst appliziertes Remote-Update, nicht zurücksenden
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

	/** Baut die Verbindung auf (bzw. stößt den ersten Reconnect-Versuch an). */
	async connect(): Promise<void> {
		this.closedByUser = false;
		if (this.status === "connecting" || this.status === "connected") return;
		await this.attemptConnect();
	}

	/** Trennt die Verbindung endgültig; es wird nicht mehr automatisch reconnected. */
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

	/** Löst alle Listener; danach ist diese Instanz nicht mehr verwendbar. */
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
	 * Sendet nach (Re-)Connect nur das Delta seit dem letzten bekannten
	 * Zustand ("Delta-Resync"), statt das komplette Dokument neu zu laden.
	 * Bei einem allerersten Connect ist die Baseline der leere State-Vector,
	 * wodurch der volle bisherige lokale Inhalt (falls vorhanden) einmalig
	 * gesendet wird.
	 */
	private performResync(): void {
		const baseline = this.preDisconnectStateVector ?? Y.encodeStateVector(new Y.Doc());
		const diff = computeOfflineDiff(this.options.doc, baseline);
		this.preDisconnectStateVector = null;
		if (!isEmptyUpdate(diff)) {
			this.sendFrame(MessageType.DocUpdate, diff);
		}

		// eigenen Awareness-Zustand nach jedem (Re-)Connect erneut bekannt geben
		const localState = this.options.awareness.getLocalState();
		if (localState !== null) {
			const update = encodeAwarenessUpdate(this.options.awareness, [this.options.awareness.clientID]);
			this.sendFrame(MessageType.AwarenessUpdate, update);
		}
	}

	private handleMessage(data: unknown): void {
		const bytes = toUint8Array(data);
		if (!bytes) {
			this.options.onError?.(new Error("Unbekanntes WebSocket-Nachrichtenformat empfangen."));
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
		}
		// SnapshotPayload wird vom Server nie an Clients zurückgesendet (nur Client->Server).
	}

	/** Antwortet auf eine REQUEST_SNAPSHOT-Server-Message mit dem vollen Dokumentzustand. */
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

function toUint8Array(data: unknown): Uint8Array | null {
	if (data instanceof Uint8Array) return data;
	if (data instanceof ArrayBuffer) return new Uint8Array(data);
	return null;
}
