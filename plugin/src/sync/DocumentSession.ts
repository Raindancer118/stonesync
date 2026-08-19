import * as Y from "yjs";
import { Awareness } from "y-protocols/awareness";
import { StoneSyncSocket, ConnectionStatus } from "../net/StoneSyncSocket";
import { requestTicket } from "../net/TicketClient";
import { toWebSocketBaseUrl } from "../settings/StoneSyncSettings";

export interface DocumentSessionOptions {
	documentId: string;
	serverUrl: string;
	apiKey: string;
	userName: string;
	userColor: string;
	onStatusChange?: (status: ConnectionStatus) => void;
	onError?: (error: unknown) => void;
}

/**
 * Eine Sync-Session pro Textdatei: ein eigenes Y.Doc (mit einem
 * "content"-Y.Text) + eine eigene Awareness-Instanz + eine eigene
 * WebSocket-Verbindung (der Server-Handshake ist pro Dokument, siehe
 * `wss://.../ws?ticket=...&documentId=<UUID>`).
 *
 * Design-Entscheidung (statt einem einzigen geteilten Y.Doc mit einem
 * Y.Text pro Datei fürs ganze Vault): das Wire-Protokoll bindet die
 * Verbindung explizit an genau eine `documentId`, und Live-Cursor-Presence
 * ist ohnehin nur unter den Nutzern relevant, die dieselbe Datei geöffnet
 * haben. Ein Y.Doc pro Datei hält die Blast-Radius klein (ein Parse-/
 * Encoding-Fehler betrifft nur ein Dokument), vermeidet unnötige
 * Netzwerklast für nicht geöffnete Dateien und spiegelt die
 * Server-Datenmodellierung (`documents`-Tabelle, ein Row pro Datei) 1:1.
 */
export class DocumentSession {
	readonly doc = new Y.Doc();
	readonly ytext = this.doc.getText("content");
	readonly awareness = new Awareness(this.doc);
	private readonly socket: StoneSyncSocket;
	private connected = false;

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
	 * Seedet ein frisches, leeres Y.Text mit dem aktuellen lokalen
	 * Dateiinhalt (nur relevant, wenn diese Datei zum allerersten Mal
	 * synchronisiert wird und noch keine Remote-Historie existiert).
	 */
	seedIfEmpty(localContent: string): void {
		if (this.ytext.length > 0 || localContent.length === 0) return;
		this.doc.transact(() => {
			this.ytext.insert(0, localContent);
		});
	}

	destroy(): void {
		this.socket.destroy();
		this.awareness.destroy();
		this.doc.destroy();
		this.connected = false;
	}
}
