import { describe, it, expect, vi } from "vitest";
import { resolveDeleteDocumentId, type DocumentIdLookup } from "./resolveDeleteDocumentId";

describe("resolveDeleteDocumentId", () => {
	it("uses the cached id without ever calling resolve()", async () => {
		const resolver: DocumentIdLookup = {
			peekId: vi.fn().mockReturnValue("cached-id"),
			resolve: vi.fn(),
		};

		const id = await resolveDeleteDocumentId(resolver, "Notes/opened.md");

		expect(id).toBe("cached-id");
		expect(resolver.resolve).not.toHaveBeenCalled();
	});

	it("falls back to a real resolve() when nothing is cached - the bug: a file that arrived " +
		"via bulk download/live vault-events and was never opened locally has no cached id, and " +
		"deleting it must still reach the server instead of silently doing nothing", async () => {
		const resolver: DocumentIdLookup = {
			peekId: vi.fn().mockReturnValue(undefined),
			resolve: vi.fn().mockResolvedValue("resolved-id"),
		};

		const id = await resolveDeleteDocumentId(resolver, "Attachments/never-opened.png");

		expect(id).toBe("resolved-id");
		expect(resolver.resolve).toHaveBeenCalledWith("Attachments/never-opened.png");
	});

	it("returns undefined without throwing when there is no resolver at all", async () => {
		const id = await resolveDeleteDocumentId(null, "Notes/x.md");

		expect(id).toBeUndefined();
	});

	it("propagates a resolve() failure (e.g. the document is genuinely gone server-side too)", async () => {
		const resolver: DocumentIdLookup = {
			peekId: vi.fn().mockReturnValue(undefined),
			resolve: vi.fn().mockRejectedValue(new Error("404")),
		};

		await expect(resolveDeleteDocumentId(resolver, "Notes/x.md")).rejects.toThrow("404");
	});
});
