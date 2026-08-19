/**
 * StoneSync wire protocol: every binary Yjs frame is prefixed with a prefix
 * byte, so that the (CRDT-unaware) server relay can distinguish between
 * document updates and awareness/presence updates without interpreting the
 * payload itself. Must exactly match `SyncMessageType.java` on the server.
 *
 * 0x00 = document update    (Y.encodeStateAsUpdate diff, persisted)
 * 0x01 = awareness update   (encodeAwarenessUpdate, routed live only)
 * 0x02 = REQUEST_SNAPSHOT   (server->client only, no payload)
 * 0x03 = SNAPSHOT_PAYLOAD   (client->server only, response to REQUEST_SNAPSHOT)
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

/** Builds a wire frame: [prefix byte][payload bytes]. Copies the payload. */
export function encodeMessage(type: MessageType, payload: Uint8Array): Uint8Array {
	const frame = new Uint8Array(payload.length + 1);
	frame[0] = type;
	frame.set(payload, 1);
	return frame;
}

/** Reads a wire frame and separates the prefix byte from the payload. */
export function decodeMessage(frame: Uint8Array): DecodedMessage {
	if (frame.length < 1) {
		throw new StoneSyncProtocolError(
			"Cannot decode empty frame (no prefix byte present)."
		);
	}

	const prefix = frame[0];
	if (!isKnownMessageType(prefix)) {
		throw new StoneSyncProtocolError(
			`Unknown prefix byte 0x${prefix.toString(16).padStart(2, "0")}.`
		);
	}

	return {
		type: prefix,
		payload: frame.slice(1),
	};
}
