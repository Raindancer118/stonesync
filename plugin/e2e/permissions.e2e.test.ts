import { beforeAll, describe, expect, it } from "vitest";
import { DocumentSession } from "../src/sync/DocumentSession";
import { DocumentIdResolver } from "../src/sync/DocumentIdResolver";
import { PermissionsClient } from "../src/access/PermissionsClient";
import { requestUrl } from "./obsidian.stub";

/**
 * The permission model against a live server, exercised the way a real client would: a viewer
 * opens the same note as an editor, tries to type, and the server has to make sure that nothing
 * they typed ever reaches anyone. Also covers the "hidden notes are never handed out" promise.
 */
const serverUrl = (process.env.STONESYNC_URL ?? "").replace(/\/+$/, "");
const apiKey = process.env.STONESYNC_API_KEY ?? "";
const vaultId = process.env.STONESYNC_VAULT_ID ?? "";
const configured = Boolean(serverUrl && apiKey && vaultId);

async function admin<T>(method: string, path: string, body?: unknown): Promise<T> {
	const response = await requestUrl({
		url: `${serverUrl}${path}`,
		method,
		headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
		body: body === undefined ? undefined : JSON.stringify(body),
	});
	if (response.status < 200 || response.status >= 300) {
		throw new Error(`${method} ${path} -> HTTP ${response.status}: ${response.text}`);
	}
	return response.json as T;
}

/** Creates a throwaway account with its own API key and a role on the test vault. */
async function createCollaborator(role: "EDITOR" | "VIEWER"): Promise<string> {
	const email = `e2e-${role.toLowerCase()}-${Date.now()}-${Math.random().toString(36).slice(2)}@example.com`;
	const user = await admin<{ id: string }>("POST", "/api/admin/users", { email, passwordHash: "x" });
	const key = await admin<{ rawKey: string }>("POST", `/api/admin/users/${user.id}/api-keys`, { deviceName: "e2e" });
	await admin("POST", `/api/admin/vaults/${vaultId}/access`, { userId: user.id, role });
	return key.rawKey;
}

function session(documentId: string, key: string, name: string, readOnly = false): DocumentSession {
	return new DocumentSession({
		documentId,
		serverUrl,
		apiKey: key,
		userName: name,
		userColor: "#64b5f6",
		readOnly,
		onError: (error) => console.error("[e2e]", error),
	});
}

async function waitFor(predicate: () => boolean, timeoutMs = 8000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (predicate()) return;
		await new Promise((resolve) => setTimeout(resolve, 50));
	}
	throw new Error("Timed out waiting for condition");
}

describe.skipIf(!configured)("permissions against a real server", () => {
	let viewerKey: string;
	let editorKey: string;

	beforeAll(async () => {
		[viewerKey, editorKey] = await Promise.all([createCollaborator("VIEWER"), createCollaborator("EDITOR")]);
	});

	it("refuses a viewer's edits over the sync socket while still relaying the editor's", async () => {
		const path = `e2e/perm-${Date.now()}.md`;
		const documentId = await new DocumentIdResolver(serverUrl, apiKey, vaultId).resolve(path);

		const owner = session(documentId, apiKey, "Owner");
		const viewer = session(documentId, viewerKey, "Viewer", true);
		try {
			await owner.connectAndWaitUntilCaughtUp(8000);
			owner.seedIfEmpty("baseline\n");

			await viewer.connectAndWaitUntilCaughtUp(8000);
			await waitFor(() => viewer.ytext.toString().includes("baseline"));

			// The viewer's client library would normally never let this happen (the editor is
			// read-only), so this simulates a tampered client talking to the server directly.
			viewer.ytext.insert(viewer.ytext.length, "viewer was here\n");
			await new Promise((resolve) => setTimeout(resolve, 1000));

			expect(owner.ytext.toString()).not.toContain("viewer was here");

			// A fresh reader gets the server's stored state - the refused write must not be in it.
			const check = session(documentId, apiKey, "Check");
			try {
				await check.connectAndWaitUntilCaughtUp(8000);
				expect(check.ytext.toString()).toContain("baseline");
				expect(check.ytext.toString()).not.toContain("viewer was here");
			} finally {
				check.destroy();
			}
		} finally {
			owner.destroy();
			viewer.destroy();
		}
	});

	it("refuses a viewer's writes over HTTP but still lets them open existing notes", async () => {
		const path = `e2e/perm-http-${Date.now()}.md`;
		await new DocumentIdResolver(serverUrl, apiKey, vaultId).resolve(path);

		const viewerResolver = new DocumentIdResolver(serverUrl, viewerKey, vaultId);
		await expect(viewerResolver.resolve(path)).resolves.toBeTruthy();
		await expect(viewerResolver.resolve(`e2e/viewer-created-${Date.now()}.md`)).rejects.toThrow();
	});

	it("never lists a note that a path rule hides from the caller", async () => {
		const secretPath = `e2e-private/secret-${Date.now()}.md`;
		await new DocumentIdResolver(serverUrl, apiKey, vaultId).resolve(secretPath);

		const ownerClient = new PermissionsClient(serverUrl, apiKey, vaultId);
		const rule = await ownerClient.setRule("e2e-private", null, "NONE");
		try {
			const viewerDocuments = await requestUrl({
				url: `${serverUrl}/api/documents?vaultId=${vaultId}`,
				headers: { Authorization: `Bearer ${viewerKey}` },
			});
			expect(viewerDocuments.text).not.toContain(secretPath);

			const viewerPermissions = await new PermissionsClient(serverUrl, viewerKey, vaultId).permissions();
			expect(viewerPermissions.rules).toContainEqual({ pathPrefix: "e2e-private", level: "NONE" });

			// The owner is not locked out by their own blanket rule.
            const ownerDocuments = await requestUrl({
				url: `${serverUrl}/api/documents?vaultId=${vaultId}`,
				headers: { Authorization: `Bearer ${apiKey}` },
			});
			expect(ownerDocuments.text).toContain(secretPath);
		} finally {
			await ownerClient.removeRule(rule.id);
		}
	});

	it("reports who changed a note and what they changed", async () => {
		const path = `e2e/history-${Date.now()}.md`;
		const documentId = await new DocumentIdResolver(serverUrl, apiKey, vaultId).resolve(path);

		const materialize = (content: string) =>
			requestUrl({
				url: `${serverUrl}/api/documents/${documentId}/materialize`,
				method: "POST",
				headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "text/plain" },
				body: content,
			});
		await materialize("first version\n");
		await materialize("first version\nsecond line\n");

		const client = new PermissionsClient(serverUrl, apiKey, vaultId);
		const history = await client.history(documentId);
		expect(history.length).toBeGreaterThanOrEqual(2);
		expect(history[0].authorEmail).toContain("@");

		const diff = await client.diff(documentId, history[0].commitId);
		expect(diff).toContain("second line");
	});
	it("explains per note who has access and where it comes from", async () => {
		const folder = `e2e-access-${Date.now()}`;
		const notePath = `${folder}/Payroll.md`;
		await new DocumentIdResolver(serverUrl, apiKey, vaultId).resolve(notePath);

		const owner = new PermissionsClient(serverUrl, apiKey, vaultId);
		const viewerId = (await new PermissionsClient(serverUrl, viewerKey, vaultId).me()).userId;
		const rule = await owner.setRule(folder, viewerId, "NONE");
		try {
			const access = await owner.accessFor(notePath);
			const viewerEntry = access.entries.find((entry) => entry.userId === viewerId);

			expect(viewerEntry?.level).toBe("NONE");
			// The rule sits on the folder, not on the note - so the dialog says "inherited" and
			// offers nothing to remove here.
			expect(viewerEntry?.inheritedFrom).toBe(folder);
			expect(viewerEntry?.exactRuleId).toBeNull();
			expect(access.entries.some((entry) => entry.userId === null)).toBe(true);

			// Setting it on the note itself gives that row a rule of its own.
			await owner.setRule(notePath, viewerId, "VIEWER");
			const afterOverride = await owner.accessFor(notePath);
			const overridden = afterOverride.entries.find((entry) => entry.userId === viewerId);
			expect(overridden?.level).toBe("VIEWER");
			expect(overridden?.exactRuleId).not.toBeNull();

			await owner.removeRule(overridden!.exactRuleId!);
			const afterRemoval = await owner.accessFor(notePath);
			expect(afterRemoval.entries.find((entry) => entry.userId === viewerId)?.level).toBe("NONE");
		} finally {
			await owner.removeRule(rule.id);
		}
	});
});
