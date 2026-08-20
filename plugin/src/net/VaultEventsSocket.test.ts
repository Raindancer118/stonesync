import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { VaultEventsSocket, type VaultEvent } from "./VaultEventsSocket";
import type { WebSocketLike } from "./StoneSyncSocket";
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
	let ticketCalls = 0;
	const getTicket = vi.fn(async () => {
		ticketCalls++;
		return `ticket-${ticketCalls}`;
	});

	const statusChanges: string[] = [];
	const events: VaultEvent[] = [];
	const errors: unknown[] = [];
	const socket = new VaultEventsSocket({
		wsBaseUrl: "wss://server.example.com",
		vaultId: "vault-123",
		getTicket,
		onEvent: (event) => events.push(event),
		onStatusChange: (s) => statusChanges.push(s),
		onError: (e) => errors.push(e),
		backoff: new ReconnectBackoff({ baseDelayMs: 100, maxDelayMs: 1000, jitter: () => 0.5 }),
		webSocketFactory: (url) => new FakeWebSocket(url),
	});

	return { socket, getTicket, statusChanges, events, errors };
}

describe("VaultEventsSocket", () => {
	beforeEach(() => {
		vi.useFakeTimers();
	});

	afterEach(() => {
		vi.useRealTimers();
	});

	it("requests a ticket and connects to the correct URL with vaultId", async () => {
		const { socket, getTicket } = makeSocket();
		await socket.connect();

		expect(getTicket).toHaveBeenCalledTimes(1);
		expect(FakeWebSocket.instances).toHaveLength(1);
		expect(FakeWebSocket.instances[0].url).toBe(
			"wss://server.example.com/ws/vault/vault-123?ticket=ticket-1"
		);
	});

	it("reports connected once the socket opens", async () => {
		const { socket, statusChanges } = makeSocket();
		await socket.connect();
		FakeWebSocket.instances[0].simulateOpen();

		expect(socket.getStatus()).toBe("connected");
		expect(statusChanges).toContain("connected");
	});

	it("parses a document_created event and forwards it via onEvent", async () => {
		const { socket, events } = makeSocket();
		await socket.connect();
		FakeWebSocket.instances[0].simulateOpen();

		FakeWebSocket.instances[0].simulateMessage(
			JSON.stringify({ type: "document_created", documentId: "doc-1", path: "a.md", contentType: "TEXT" })
		);

		expect(events).toHaveLength(1);
		expect(events[0]).toEqual({ type: "document_created", documentId: "doc-1", path: "a.md", contentType: "TEXT" });
	});

	it("parses a document_deleted event and forwards it via onEvent", async () => {
		const { socket, events } = makeSocket();
		await socket.connect();
		FakeWebSocket.instances[0].simulateOpen();

		FakeWebSocket.instances[0].simulateMessage(
			JSON.stringify({ type: "document_deleted", documentId: "doc-1", path: "a.md" })
		);

		expect(events).toHaveLength(1);
		expect(events[0]).toEqual({ type: "document_deleted", documentId: "doc-1", path: "a.md" });
	});

	it("reports an error for malformed JSON instead of throwing", async () => {
		const { socket, events, errors } = makeSocket();
		await socket.connect();
		FakeWebSocket.instances[0].simulateOpen();

		expect(() => FakeWebSocket.instances[0].simulateMessage("not json")).not.toThrow();
		expect(events).toHaveLength(0);
		expect(errors).toHaveLength(1);
	});

	it("reports an error for a non-text (binary) message instead of throwing", async () => {
		const { socket, errors } = makeSocket();
		await socket.connect();
		FakeWebSocket.instances[0].simulateOpen();

		FakeWebSocket.instances[0].simulateMessage(new ArrayBuffer(4));

		expect(errors).toHaveLength(1);
	});

	it("reconnects with backoff after the connection drops unexpectedly", async () => {
		const { socket, getTicket } = makeSocket();
		await socket.connect();
		FakeWebSocket.instances[0].simulateOpen();

		FakeWebSocket.instances[0].simulateServerClose();
		expect(socket.getStatus()).toBe("reconnecting");

		await vi.advanceTimersByTimeAsync(100);
		expect(getTicket).toHaveBeenCalledTimes(2);
		expect(FakeWebSocket.instances).toHaveLength(2);
	});

	it("does not reconnect after disconnect() was called explicitly", async () => {
		const { socket } = makeSocket();
		await socket.connect();
		FakeWebSocket.instances[0].simulateOpen();

		socket.disconnect();
		expect(socket.getStatus()).toBe("closed");

		await vi.advanceTimersByTimeAsync(5000);
		expect(FakeWebSocket.instances).toHaveLength(1);
	});

	it("stops retrying and reports unauthorized when the ticket request fails with 401/403", async () => {
		FakeWebSocket.instances = [];
		const getTicket = vi.fn(async () => {
			throw { status: 401 };
		});
		const statusChanges: string[] = [];
		const socket = new VaultEventsSocket({
			wsBaseUrl: "wss://server.example.com",
			vaultId: "vault-123",
			getTicket,
			onEvent: () => {},
			onStatusChange: (s) => statusChanges.push(s),
			backoff: new ReconnectBackoff({ baseDelayMs: 100, maxDelayMs: 1000, jitter: () => 0.5 }),
			webSocketFactory: (url) => new FakeWebSocket(url),
		});

		await socket.connect();

		expect(socket.getStatus()).toBe("unauthorized");
		expect(statusChanges).toContain("unauthorized");

		await vi.advanceTimersByTimeAsync(5000);
		expect(getTicket).toHaveBeenCalledTimes(1);
	});
});
