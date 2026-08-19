import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as Y from "yjs";
import { Awareness } from "y-protocols/awareness";
import { StoneSyncSocket, WebSocketLike, isAuthError } from "./StoneSyncSocket";
import { decodeMessage, MessageType } from "../protocol/prefix";
import { ReconnectBackoff } from "./backoff";

/** Minimal, controllable fake implementation of WebSocketLike for tests. */
class FakeWebSocket implements WebSocketLike {
	static instances: FakeWebSocket[] = [];
	readyState = 0; // CONNECTING
	binaryType = "";
	onopen: (() => void) | null = null;
	onclose: (() => void) | null = null;
	onerror: ((err: unknown) => void) | null = null;
	onmessage: ((event: { data: unknown }) => void) | null = null;
	sent: unknown[] = [];

	constructor(public url: string) {
		FakeWebSocket.instances.push(this);
	}

	send(data: ArrayBufferLike | ArrayBufferView | string): void {
		this.sent.push(data);
	}

	close(): void {
		this.readyState = 3; // CLOSED
		this.onclose?.();
	}

	simulateOpen(): void {
		this.readyState = 1; // OPEN
		this.onopen?.();
	}

	simulateMessage(data: unknown): void {
		this.onmessage?.({ data });
	}

	simulateServerClose(): void {
		this.readyState = 3;
		this.onclose?.();
	}
}

function makeSocket() {
	FakeWebSocket.instances = [];
	const doc = new Y.Doc();
	const awareness = new Awareness(doc);
	let ticketCalls = 0;
	const getTicket = vi.fn(async () => {
		ticketCalls++;
		return `ticket-${ticketCalls}`;
	});

	const statusChanges: string[] = [];
	const socket = new StoneSyncSocket({
		wsBaseUrl: "wss://server.example.com",
		documentId: "doc-123",
		getTicket,
		doc,
		awareness,
		backoff: new ReconnectBackoff({ baseDelayMs: 100, maxDelayMs: 1000, jitter: () => 0.5 }),
		webSocketFactory: (url) => new FakeWebSocket(url),
		onStatusChange: (s) => statusChanges.push(s),
	});

	return { socket, doc, awareness, getTicket, statusChanges };
}

describe("StoneSyncSocket", () => {
	beforeEach(() => {
		vi.useFakeTimers();
	});

	afterEach(() => {
		vi.useRealTimers();
	});

	it("requests a ticket and connects to the correct URL with documentId", async () => {
		const { socket, getTicket } = makeSocket();
		await socket.connect();

		expect(getTicket).toHaveBeenCalledTimes(1);
		expect(FakeWebSocket.instances).toHaveLength(1);
		expect(FakeWebSocket.instances[0].url).toBe(
			"wss://server.example.com/ws/sync/doc-123?ticket=ticket-1"
		);
	});

	it("sends local doc updates as 0x00-prefixed frames once connected", async () => {
		const { socket, doc } = makeSocket();
		await socket.connect();
		const ws = FakeWebSocket.instances[0];
		ws.simulateOpen();
		ws.sent = []; // clear the initial resync frame

		doc.getText("content").insert(0, "hello");

		expect(ws.sent).toHaveLength(1);
		const frame = ws.sent[0] as Uint8Array;
		const decoded = decodeMessage(frame);
		expect(decoded.type).toBe(MessageType.DocUpdate);
	});

	it("sends awareness updates as 0x01-prefixed frames", async () => {
		const { socket, awareness } = makeSocket();
		await socket.connect();
		const ws = FakeWebSocket.instances[0];
		ws.simulateOpen();
		ws.sent = [];

		awareness.setLocalStateField("cursor", { line: 3, ch: 1 });

		expect(ws.sent.length).toBeGreaterThanOrEqual(1);
		const decoded = decodeMessage(ws.sent[ws.sent.length - 1] as Uint8Array);
		expect(decoded.type).toBe(MessageType.AwarenessUpdate);
	});

	it("applies an incoming 0x00 doc-update frame to the local Y.Doc", async () => {
		const { socket, doc } = makeSocket();
		await socket.connect();
		const ws = FakeWebSocket.instances[0];
		ws.simulateOpen();

		const remoteDoc = new Y.Doc();
		remoteDoc.getText("content").insert(0, "from remote");
		const update = Y.encodeStateAsUpdate(remoteDoc);
		const frame = new Uint8Array(update.length + 1);
		frame[0] = 0x00;
		frame.set(update, 1);

		ws.simulateMessage(frame);

		expect(doc.getText("content").toString()).toBe("from remote");
	});

	it("applies an incoming 0x01 awareness-update frame", async () => {
		const { socket, awareness } = makeSocket();
		await socket.connect();
		const ws = FakeWebSocket.instances[0];
		ws.simulateOpen();

		const remoteDoc = new Y.Doc();
		const remoteAwareness = new Awareness(remoteDoc);
		remoteAwareness.setLocalStateField("user", { name: "Remote User" });
		const { encodeAwarenessUpdate } = await import("y-protocols/awareness");
		const update = encodeAwarenessUpdate(remoteAwareness, [remoteAwareness.clientID]);
		const frame = new Uint8Array(update.length + 1);
		frame[0] = 0x01;
		frame.set(update, 1);

		ws.simulateMessage(frame);

		const states = Array.from(awareness.getStates().values());
		expect(states.some((s) => (s as { user?: { name?: string } }).user?.name === "Remote User")).toBe(
			true
		);
	});

	it("replies with a binary 0x03 SNAPSHOT_PAYLOAD frame on a 0x02 REQUEST_SNAPSHOT frame", async () => {
		const { socket, doc } = makeSocket();
		doc.getText("content").insert(0, "snapshot me");
		await socket.connect();
		const ws = FakeWebSocket.instances[0];
		ws.simulateOpen();
		ws.sent = [];

		// Server sends REQUEST_SNAPSHOT as a pure prefix byte with no payload.
		ws.simulateMessage(new Uint8Array([0x02]));

		expect(ws.sent).toHaveLength(1);
		const decoded = decodeMessage(ws.sent[0] as Uint8Array);
		expect(decoded.type).toBe(MessageType.SnapshotPayload);

		const target = new Y.Doc();
		Y.applyUpdate(target, decoded.payload);
		expect(target.getText("content").toString()).toBe("snapshot me");
	});

	it("reconnects with backoff after an unexpected close, and resyncs local edits made offline", async () => {
		const { socket, doc } = makeSocket();
		await socket.connect();
		const firstWs = FakeWebSocket.instances[0];
		firstWs.simulateOpen();

		// connection drops unexpectedly (not via disconnect())
		firstWs.simulateServerClose();

		// local edit made while offline
		doc.getText("content").insert(0, "offline edit");

		// backoff base delay is 100ms
		await vi.advanceTimersByTimeAsync(100);

		expect(FakeWebSocket.instances).toHaveLength(2);
		const secondWs = FakeWebSocket.instances[1];
		secondWs.simulateOpen();

		// resync sends the offline doc-diff, plus a re-announcement of the
		// local awareness state (presence) after reconnecting
		expect(secondWs.sent.length).toBeGreaterThanOrEqual(1);
		const decoded = decodeMessage(secondWs.sent[0] as Uint8Array);
		expect(decoded.type).toBe(MessageType.DocUpdate);

		const target = new Y.Doc();
		Y.applyUpdate(target, decoded.payload);
		expect(target.getText("content").toString()).toBe("offline edit");
	});

	it("does not reconnect after an explicit disconnect()", async () => {
		const { socket } = makeSocket();
		await socket.connect();
		const ws = FakeWebSocket.instances[0];
		ws.simulateOpen();

		socket.disconnect();
		await vi.advanceTimersByTimeAsync(5000);

		expect(FakeWebSocket.instances).toHaveLength(1);
		expect(socket.getStatus()).toBe("closed");
	});

	it("reports connection status transitions", async () => {
		const { socket, statusChanges } = makeSocket();
		await socket.connect();
		const ws = FakeWebSocket.instances[0];
		ws.simulateOpen();

		expect(statusChanges).toContain("connecting");
		expect(statusChanges).toContain("connected");
	});

	it("stops retrying and reports status 'unauthorized' when getTicket() fails with HTTP 401", async () => {
		FakeWebSocket.instances = [];
		const doc = new Y.Doc();
		const awareness = new Awareness(doc);
		const statusChanges: string[] = [];
		const getTicket = vi.fn(async () => {
			throw Object.assign(new Error("invalid api key"), { status: 401 });
		});
		const socket = new StoneSyncSocket({
			wsBaseUrl: "wss://server.example.com",
			documentId: "doc-123",
			getTicket,
			doc,
			awareness,
			backoff: new ReconnectBackoff({ baseDelayMs: 100, maxDelayMs: 1000, jitter: () => 0.5 }),
			webSocketFactory: (url) => new FakeWebSocket(url),
			onStatusChange: (s) => statusChanges.push(s),
		});

		await socket.connect();

		expect(socket.getStatus()).toBe("unauthorized");
		expect(getTicket).toHaveBeenCalledTimes(1);

		// Must NOT schedule a reconnect - advancing time should not trigger a second attempt.
		await vi.advanceTimersByTimeAsync(10_000);
		expect(getTicket).toHaveBeenCalledTimes(1);
		expect(FakeWebSocket.instances).toHaveLength(0);
	});

	it("keeps retrying with backoff on a non-auth ticket error (e.g. network failure)", async () => {
		FakeWebSocket.instances = [];
		const doc = new Y.Doc();
		const awareness = new Awareness(doc);
		let calls = 0;
		const getTicket = vi.fn(async () => {
			calls++;
			if (calls === 1) throw new Error("network unreachable");
			return "ticket-2";
		});
		const socket = new StoneSyncSocket({
			wsBaseUrl: "wss://server.example.com",
			documentId: "doc-123",
			getTicket,
			doc,
			awareness,
			backoff: new ReconnectBackoff({ baseDelayMs: 100, maxDelayMs: 1000, jitter: () => 0.5 }),
			webSocketFactory: (url) => new FakeWebSocket(url),
		});

		await socket.connect();
		expect(socket.getStatus()).not.toBe("unauthorized");

		await vi.advanceTimersByTimeAsync(100);
		expect(getTicket).toHaveBeenCalledTimes(2);
		expect(FakeWebSocket.instances).toHaveLength(1);
	});
});

describe("StoneSyncSocket catch-up and delete notice", () => {
	beforeEach(() => {
		vi.useFakeTimers();
	});

	afterEach(() => {
		vi.useRealTimers();
	});

	it("fires onCaughtUp when a 0x04 CAUGHT_UP frame is received", async () => {
		FakeWebSocket.instances = [];
		const doc = new Y.Doc();
		const awareness = new Awareness(doc);
		let caughtUp = 0;
		const socket = new StoneSyncSocket({
			wsBaseUrl: "wss://server.example.com",
			documentId: "doc-123",
			getTicket: vi.fn(async () => "ticket-1"),
			doc,
			awareness,
			backoff: new ReconnectBackoff({ baseDelayMs: 100, maxDelayMs: 1000, jitter: () => 0.5 }),
			webSocketFactory: (url) => new FakeWebSocket(url),
			onCaughtUp: () => caughtUp++,
		});

		await socket.connect();
		const ws = FakeWebSocket.instances[0];
		ws.simulateOpen();

		ws.simulateMessage(new Uint8Array([0x04]));

		expect(caughtUp).toBe(1);
	});

	it("fires onDeleteNotice when a 0x06 DELETE_NOTICE frame is received", async () => {
		FakeWebSocket.instances = [];
		const doc = new Y.Doc();
		const awareness = new Awareness(doc);
		let deleteNotices = 0;
		const socket = new StoneSyncSocket({
			wsBaseUrl: "wss://server.example.com",
			documentId: "doc-123",
			getTicket: vi.fn(async () => "ticket-1"),
			doc,
			awareness,
			backoff: new ReconnectBackoff({ baseDelayMs: 100, maxDelayMs: 1000, jitter: () => 0.5 }),
			webSocketFactory: (url) => new FakeWebSocket(url),
			onDeleteNotice: () => deleteNotices++,
		});

		await socket.connect();
		const ws = FakeWebSocket.instances[0];
		ws.simulateOpen();

		ws.simulateMessage(new Uint8Array([0x06]));

		expect(deleteNotices).toBe(1);
	});
});

describe("isAuthError", () => {
	it("recognizes HTTP 401 and 403 as auth errors", () => {
		expect(isAuthError({ status: 401 })).toBe(true);
		expect(isAuthError({ status: 403 })).toBe(true);
	});

	it("does not treat other statuses or non-status errors as auth errors", () => {
		expect(isAuthError({ status: 500 })).toBe(false);
		expect(isAuthError(new Error("network failure"))).toBe(false);
		expect(isAuthError(null)).toBe(false);
		expect(isAuthError("plain string")).toBe(false);
	});
});
