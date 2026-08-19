import { describe, expect, it } from "vitest";
import {
	MessageType,
	encodeMessage,
	decodeMessage,
	StoneSyncProtocolError,
} from "./prefix";

describe("prefix protocol", () => {
	it("encodes a document update with the 0x00 prefix byte", () => {
		const payload = new Uint8Array([1, 2, 3, 4]);
		const encoded = encodeMessage(MessageType.DocUpdate, payload);

		expect(encoded[0]).toBe(0x00);
		expect(encoded.length).toBe(payload.length + 1);
		expect(encoded.slice(1)).toEqual(payload);
	});

	it("encodes an awareness update with the 0x01 prefix byte", () => {
		const payload = new Uint8Array([9, 8, 7]);
		const encoded = encodeMessage(MessageType.AwarenessUpdate, payload);

		expect(encoded[0]).toBe(0x01);
		expect(encoded.slice(1)).toEqual(payload);
	});

	it("round-trips document updates through encode/decode", () => {
		const payload = new Uint8Array([42, 13, 255, 0, 128]);
		const decoded = decodeMessage(encodeMessage(MessageType.DocUpdate, payload));

		expect(decoded.type).toBe(MessageType.DocUpdate);
		expect(decoded.payload).toEqual(payload);
	});

	it("round-trips awareness updates through encode/decode", () => {
		const payload = new Uint8Array([5, 6, 7]);
		const decoded = decodeMessage(
			encodeMessage(MessageType.AwarenessUpdate, payload)
		);

		expect(decoded.type).toBe(MessageType.AwarenessUpdate);
		expect(decoded.payload).toEqual(payload);
	});

	it("handles an empty payload correctly", () => {
		const decoded = decodeMessage(
			encodeMessage(MessageType.DocUpdate, new Uint8Array(0))
		);

		expect(decoded.type).toBe(MessageType.DocUpdate);
		expect(decoded.payload.length).toBe(0);
	});

	it("throws StoneSyncProtocolError when decoding an empty frame", () => {
		expect(() => decodeMessage(new Uint8Array(0))).toThrow(
			StoneSyncProtocolError
		);
	});

	it("throws StoneSyncProtocolError for an unknown prefix byte", () => {
		const frame = new Uint8Array([0x99, 1, 2, 3]);
		expect(() => decodeMessage(frame)).toThrow(StoneSyncProtocolError);
	});

	it("decodes a single-byte REQUEST_SNAPSHOT frame (0x02, no payload)", () => {
		const decoded = decodeMessage(new Uint8Array([0x02]));
		expect(decoded.type).toBe(MessageType.RequestSnapshot);
		expect(decoded.payload.length).toBe(0);
	});

	it("round-trips a SNAPSHOT_PAYLOAD (0x03) frame", () => {
		const payload = new Uint8Array([10, 20, 30]);
		const decoded = decodeMessage(encodeMessage(MessageType.SnapshotPayload, payload));
		expect(decoded.type).toBe(MessageType.SnapshotPayload);
		expect(decoded.payload).toEqual(payload);
	});

	it("does not mutate the source payload buffer", () => {
		const payload = new Uint8Array([1, 2, 3]);
		const encoded = encodeMessage(MessageType.DocUpdate, payload);
		encoded[1] = 99;
		expect(payload[0]).toBe(1);
	});
});
