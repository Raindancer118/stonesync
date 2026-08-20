import { beforeAll, describe, expect, it } from "vitest";
import * as Y from "yjs";
import { DocumentSession } from "../src/sync/DocumentSession";
import { DocumentIdResolver } from "../src/sync/DocumentIdResolver";
import { LinkClient } from "../src/links/LinkClient";
import { applyLinkRewrite } from "../src/links/linkRewrites";
import { requestUrl } from "./obsidian.stub";

/**
 * Cross-vault linking against a live server: a note in one vault links into another, the link is
 * only followable by someone who may read the target, and renaming the target queues a repair
 * that a client applies as an ordinary edit - the server never touches Yjs itself.
 */
const serverUrl = (process.env.STONESYNC_URL ?? "").replace(/\/+$/, "");
const apiKey = process.env.STONESYNC_API_KEY ?? "";
const vaultId = process.env.STONESYNC_VAULT_ID ?? "";
const configured = Boolean(serverUrl && apiKey && vaultId);

async function api<T>(method: string, path: string, body?: unknown, key = apiKey, contentType = "application/json"): Promise<T> {
	const response = await requestUrl({
		url: `${serverUrl}${path}`,
		method,
		headers: { Authorization: `Bearer ${key}`, "Content-Type": contentType },
		body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
	});
	if (response.status < 200 || response.status >= 300) {
		throw new Error(`${method} ${path} -> HTTP ${response.status}: ${response.text}`);
	}
	return response.json as T;
}

describe.skipIf(!configured)("cross-vault links against a real server", () => {
	const suffix = Date.now().toString(36);
	const slug = `e2e-docs-${suffix}`;
	let otherVaultId: string;
	let ownUserId: string;
	let strangerKey: string;

	beforeAll(async () => {
		const me = await api<{ userId: string }>("GET", "/api/me");
		ownUserId = me.userId;

		// A second vault, owned by us, reachable under its own namespace.
		const vault = await api<{ id: string }>("POST", "/api/admin/vaults", { name: `E2E Docs ${suffix}`, ownerId: ownUserId });
		otherVaultId = vault.id;
		await api("POST", `/api/admin/vaults/${otherVaultId}/access`, { userId: ownUserId, role: "OWNER" });
		await api("PUT", `/api/vaults/${otherVaultId}/slug`, { slug });

		// Someone who has no access to that second vault at all.
		const stranger = await api<{ id: string }>("POST", "/api/admin/users", {
			email: `e2e-stranger-${suffix}@example.com`,
			passwordHash: "x",
		});
		const key = await api<{ rawKey: string }>("POST", `/api/admin/users/${stranger.id}/api-keys`, { deviceName: "e2e" });
		strangerKey = key.rawKey;
		await api("POST", `/api/admin/vaults/${vaultId}/access`, { userId: stranger.id, role: "EDITOR" });
	});

	it("resolves a namespaced link for someone who may read the target, and hides it from everyone else", async () => {
		const targetPath = `Referenz/API-${suffix}.md`;
		await new DocumentIdResolver(serverUrl, apiKey, otherVaultId).resolve(targetPath);

		const mine = await new LinkClient(serverUrl, apiKey).resolve(slug, `Referenz/API-${suffix}`);
		expect(mine.status).toBe("AVAILABLE");
		expect(mine.path).toBe(targetPath);
		expect(mine.writable).toBe(true);

		const theirs = await new LinkClient(serverUrl, strangerKey).resolve(slug, `Referenz/API-${suffix}`);
		expect(theirs.status).toBe("RESTRICTED");
		expect(theirs.documentId).toBeNull();
		expect(theirs.path).toBeNull();
	});

	it("indexes only cross-vault links and reports permission-filtered backlinks", async () => {
		const targetPath = `Onboarding-${suffix}.md`;
		const targetId = await new DocumentIdResolver(serverUrl, apiKey, otherVaultId).resolve(targetPath);
		const sourcePath = `e2e/links-${suffix}.md`;
		const sourceId = await new DocumentIdResolver(serverUrl, apiKey, vaultId).resolve(sourcePath);

		await api("POST", `/api/documents/${sourceId}/materialize`,
			`Siehe [[${slug}:Onboarding-${suffix}]] und [[Eine Lokale Notiz]].\n`, apiKey, "text/plain");

		const backlinks = await new LinkClient(serverUrl, apiKey).backlinks(targetId);
		expect(backlinks.map((link) => link.path)).toContain(sourcePath);
		expect(JSON.stringify(backlinks)).not.toContain("Eine Lokale Notiz");
	});

	it("repairs links in other vaults when the target is renamed", async () => {
		const targetPath = `Handbuch-${suffix}.md`;
		const targetId = await new DocumentIdResolver(serverUrl, apiKey, otherVaultId).resolve(targetPath);
		const sourcePath = `e2e/rename-source-${suffix}.md`;
		const sourceId = await new DocumentIdResolver(serverUrl, apiKey, vaultId).resolve(sourcePath);
		const originalLink = `[[${slug}:Handbuch-${suffix}|das Handbuch]]`;

		// The note that links there, with its content known to the server via materialize.
		const source = new DocumentSession({
			documentId: sourceId,
			serverUrl,
			apiKey,
			userName: "Author",
			userColor: "#81c784",
			onError: (error) => console.error("[e2e]", error),
		});
		try {
			await source.connectAndWaitUntilCaughtUp(8000);
			source.seedIfEmpty(`Lies ${originalLink} zuerst.\n`);
			await api("POST", `/api/documents/${sourceId}/materialize`, source.ytext.toString(), apiKey, "text/plain");

			const newPath = `Referenz/Handbuch v2-${suffix}.md`;
			await requestUrl({
				url: `${serverUrl}/api/documents/${targetId}/path`,
				method: "PATCH",
				headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
				body: JSON.stringify({ newPath }),
			});

			const client = new LinkClient(serverUrl, apiKey);
			const pending = await client.pendingRewrites(sourceId);
			expect(pending).toHaveLength(1);
			expect(pending[0].oldLink).toBe(originalLink);
			expect(pending[0].newLink).toBe(`[[${slug}:Referenz/Handbuch v2-${suffix}|das Handbuch]]`);

			// The client performs the edit - and it reaches other clients like any other edit.
			const other = new DocumentSession({
				documentId: sourceId,
				serverUrl,
				apiKey,
				userName: "Colleague",
				userColor: "#64b5f6",
				readOnly: true,
				onError: (error) => console.error("[e2e]", error),
			});
			try {
				await other.connectAndWaitUntilCaughtUp(8000);

				const replaced = applyLinkRewrite(source.ytext, pending[0].oldLink, pending[0].newLink);
				expect(replaced).toBe(1);
				await client.markRewriteApplied(sourceId, pending[0].id);

				const deadline = Date.now() + 8000;
				while (Date.now() < deadline && !other.ytext.toString().includes("Handbuch v2")) {
					await new Promise((resolve) => setTimeout(resolve, 50));
				}
				expect(other.ytext.toString()).toContain(`[[${slug}:Referenz/Handbuch v2-${suffix}|das Handbuch]]`);
				expect(await client.pendingRewrites(sourceId)).toHaveLength(0);
			} finally {
				other.destroy();
			}
		} finally {
			source.destroy();
		}
	});

	it("leaves a purely local vault working without any of this - plain links are never indexed", async () => {
		const sourcePath = `e2e/local-only-${suffix}.md`;
		const sourceId = await new DocumentIdResolver(serverUrl, apiKey, vaultId).resolve(sourcePath);
		await api("POST", `/api/documents/${sourceId}/materialize`,
			"Nur lokale Links: [[Startseite]], [[Ordner/Notiz|Alias]], [[Notiz#Abschnitt]].\n", apiKey, "text/plain");

		// Nothing to resolve, nothing queued, nothing for the server to do.
		const doc = new Y.Doc();
		const ytext = doc.getText("content");
		ytext.insert(0, "[[Startseite]]");
		expect(applyLinkRewrite(ytext, "[[Startseite]]", "[[Etwas Anderes]]")).toBe(1); // only if asked explicitly
		expect(await new LinkClient(serverUrl, apiKey).pendingRewrites(sourceId)).toHaveLength(0);
	});
});
