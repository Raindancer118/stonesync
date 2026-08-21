import { describe, expect, it } from "vitest";
import { DocumentIdResolver } from "../src/sync/DocumentIdResolver";
import { materializeDocument } from "../src/sync/MaterializeClient";
import { searchVault } from "../src/search/SearchClient";

/**
 * The plugin's own search client against a real server: materialize a note's plaintext (the same
 * side-channel the real sync flow uses - see MaterializeClient), then confirm the JSON search
 * endpoint (`GET /api/documents/search`, DocumentController#search) finds it, snippet and all -
 * this is what StoneSyncQuickSearchModal/StoneSyncHomeView actually call.
 *
 * Requires a running server; configured via env (see e2e/README.md). Skipped otherwise.
 */
const serverUrl = process.env.STONESYNC_URL ?? "";
const apiKey = process.env.STONESYNC_API_KEY ?? "";
const vaultId = process.env.STONESYNC_VAULT_ID ?? "";
const configured = Boolean(serverUrl && apiKey && vaultId);

async function waitFor<T>(fn: () => Promise<T>, predicate: (value: T) => boolean, timeoutMs = 10000): Promise<T> {
	const deadline = Date.now() + timeoutMs;
	let last: T;
	while (Date.now() < deadline) {
		last = await fn();
		if (predicate(last)) return last;
		await new Promise((resolve) => setTimeout(resolve, 200));
	}
	return last!;
}

describe.skipIf(!configured)("search against a real server", () => {
	it("finds a materialized note by content and returns a highlighted snippet", async () => {
		const path = `e2e/search-${Date.now()}-${Math.random().toString(36).slice(2)}.md`;
		const resolver = new DocumentIdResolver(serverUrl, apiKey, vaultId);
		const documentId = await resolver.resolve(path);
		await materializeDocument(serverUrl, apiKey, documentId, "The quarterly roadmap review happens on Friday.");

		const hits = await waitFor(
			() => searchVault(serverUrl, apiKey, vaultId, "roadmap"),
			(results) => results.some((hit) => hit.id === documentId)
		);

		const hit = hits.find((h) => h.id === documentId);
		expect(hit).toBeDefined();
		expect(hit?.path).toBe(path);
		expect(hit?.contentType).toBe("TEXT");
		expect(hit?.snippetHtml).toContain("<mark>roadmap</mark>");
	});

	it("tolerates a garbled title typo via fuzzy trigram matching on the note's path", async () => {
		// The real-world scenario this feature targets: the user types a mangled version of a
		// note's *title* ("Moatseting" for "Monatsmeeting") - a short, folder-qualified path is
		// what word_similarity is well-behaved against (see DocumentRepository#searchRaw's
		// javadoc: unlike fuzzy-matching page-length plain_text at this vault's real scale,
		// title/path fuzzy matching stays precise since paths are short).
		const path = `e2e/search-fuzzy-Monatsmeeting-${Date.now()}.md`;
		const resolver = new DocumentIdResolver(serverUrl, apiKey, vaultId);
		const documentId = await resolver.resolve(path);
		await materializeDocument(serverUrl, apiKey, documentId, "Agenda: budget review, roadmap update.");

		const hits = await waitFor(
			() => searchVault(serverUrl, apiKey, vaultId, "Moatseting"),
			(results) => results.some((hit) => hit.id === documentId)
		);

		expect(hits.some((hit) => hit.id === documentId)).toBe(true);
	});
});
