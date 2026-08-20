import { beforeAll, describe, expect, it } from "vitest";
import { DocumentSession } from "../src/sync/DocumentSession";
import { DocumentIdResolver } from "../src/sync/DocumentIdResolver";

/**
 * Two independent clients on the same document, against a live server - the exact scenario that
 * was broken in the field ("same note open twice, no cursors, edits never arrive"). Covers the
 * full stack: ticket handshake, WebSocket, prefix protocol, Yjs update relay, awareness relay
 * (live cursor presence) and the seed-after-catch-up ordering that must not duplicate content.
 *
 * Requires a running server; configured via env (see e2e/README.md). Skipped otherwise.
 */
const serverUrl = process.env.STONESYNC_URL ?? "";
const apiKey = process.env.STONESYNC_API_KEY ?? "";
const vaultId = process.env.STONESYNC_VAULT_ID ?? "";
const configured = Boolean(serverUrl && apiKey && vaultId);

function makeSession(documentId: string, userName: string, color: string): DocumentSession {
	return new DocumentSession({
		documentId,
		serverUrl,
		apiKey,
		userName,
		userColor: color,
		onError: (error) => console.error("[e2e]", error),
	});
}

async function waitFor(predicate: () => boolean, timeoutMs = 10000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 50));
	}
	throw new Error("Timed out waiting for condition");
}

describe.skipIf(!configured)("live collaboration against a real server", () => {
	let documentId: string;

	beforeAll(async () => {
		const resolver = new DocumentIdResolver(serverUrl, apiKey, vaultId);
		documentId = await resolver.resolve(`e2e/live-${Date.now()}-${Math.random().toString(36).slice(2)}.md`);
	});

	it("propagates edits and cursor presence between two clients", async () => {
		const alice = makeSession(documentId, "Alice", "#e57373");
		const bob = makeSession(documentId, "Bob", "#64b5f6");
		try {
			expect(await alice.connectAndWaitUntilCaughtUp(10000)).toBe(true);
			alice.seedIfEmpty("# Shared note\n");

			// Bob joins afterwards and must receive the existing content from the server's replay.
			expect(await bob.connectAndWaitUntilCaughtUp(10000)).toBe(true);
			await waitFor(() => bob.ytext.toString() === "# Shared note\n");

			// Bob would have seeded his identical local copy here before the fix - the CRDT would
			// then hold the content twice. decideReconciliation()/seedIfEmpty must both no-op.
			bob.seedIfEmpty("# Shared note\n");
			expect(bob.ytext.toString()).toBe("# Shared note\n");

			// Live typing, in both directions.
			alice.ytext.insert(alice.ytext.length, "typed by Alice\n");
			await waitFor(() => bob.ytext.toString().includes("typed by Alice"));

			bob.ytext.insert(bob.ytext.length, "typed by Bob\n");
			await waitFor(() => alice.ytext.toString().includes("typed by Bob"));
			expect(alice.ytext.toString()).toBe(bob.ytext.toString());

			// Live cursor presence: each side sees the other as a peer (this is what the remote
			// selection decorations in the editor are rendered from).
			await waitFor(() => bob.peers().some((peer) => peer.name === "Alice"));
			await waitFor(() => alice.peers().some((peer) => peer.name === "Bob"));
			expect(bob.peers().find((peer) => peer.name === "Alice")?.color).toBe("#e57373");

			// ... and the cursor disappears again the moment a collaborator leaves.
			alice.destroy();
			await waitFor(() => bob.peers().length === 0);
		} finally {
			alice.destroy();
			bob.destroy();
		}
	});

	it("lets a client that connects later catch up on the full history", async () => {
		const author = makeSession(documentId, "Author", "#81c784");
		try {
			await author.connectAndWaitUntilCaughtUp(10000);
			author.ytext.insert(author.ytext.length, "late-joiner marker\n");
			await new Promise((resolve) => setTimeout(resolve, 500));

			const latecomer = makeSession(documentId, "Latecomer", "#ffb74d");
			try {
				expect(await latecomer.connectAndWaitUntilCaughtUp(10000)).toBe(true);
				expect(latecomer.ytext.toString()).toContain("late-joiner marker");
			} finally {
				latecomer.destroy();
			}
		} finally {
			author.destroy();
		}
	});
});
