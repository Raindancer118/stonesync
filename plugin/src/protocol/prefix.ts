/**
 * StoneSync Wire-Protokoll: jedem binären Yjs-Frame wird ein Präfix-Byte
 * vorangestellt, damit der (CRDT-unwissende) Server-Relay zwischen
 * Dokument-Updates und Awareness/Presence-Updates unterscheiden kann,
 * ohne den Payload selbst zu interpretieren. Muss exakt
 * `SyncMessageType.java` auf dem Server entsprechen.
 *
 * 0x00 = Dokument-Update    (Y.encodeStateAsUpdate-Diff, wird persistiert)
 * 0x01 = Awareness-Update   (encodeAwarenessUpdate, nur live geroutet)
 * 0x02 = REQUEST_SNAPSHOT   (nur Server->Client, kein Payload)
 * 0x03 = SNAPSHOT_PAYLOAD   (nur Client->Server, Antwort auf REQUEST_SNAPSHOT)
 */
export enum MessageType {
	DocUpdate = 0x00,
	AwarenessUpdate = 0x01,
	RequestSnapshot = 0x02,
	SnapshotPayload = 0x03,
}

export class StoneSyncProtocolError extends Error {
	constructor(message: string) {
		super(message);
		this.name = "StoneSyncProtocolError";
	}
}

export interface DecodedMessage {
	type: MessageType;
	payload: Uint8Array;
}

function isKnownMessageType(value: number): value is MessageType {
	return (
		value === MessageType.DocUpdate ||
		value === MessageType.AwarenessUpdate ||
		value === MessageType.RequestSnapshot ||
		value === MessageType.SnapshotPayload
	);
}

/** Baut ein Wire-Frame: [Präfix-Byte][Payload-Bytes]. Kopiert den Payload. */
export function encodeMessage(type: MessageType, payload: Uint8Array): Uint8Array {
	const frame = new Uint8Array(payload.length + 1);
	frame[0] = type;
	frame.set(payload, 1);
	return frame;
}

/** Liest ein Wire-Frame und trennt Präfix-Byte von Payload. */
export function decodeMessage(frame: Uint8Array): DecodedMessage {
	if (frame.length < 1) {
		throw new StoneSyncProtocolError(
			"Leeres Frame kann nicht dekodiert werden (kein Präfix-Byte vorhanden)."
		);
	}

	const prefix = frame[0];
	if (!isKnownMessageType(prefix)) {
		throw new StoneSyncProtocolError(
			`Unbekanntes Präfix-Byte 0x${prefix.toString(16).padStart(2, "0")}.`
		);
	}

	return {
		type: prefix,
		payload: frame.slice(1),
	};
}
