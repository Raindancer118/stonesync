import { describe, expect, it } from "vitest";
import { ensureParentFolders } from "./ensureParentFolders";

/**
 * Minimal fake of the subset of Obsidian's `DataAdapter` that `ensureParentFolders`
 * needs - pure in-memory logic, no real filesystem/Obsidian runtime involved.
 */
class FakeAdapter {
	readonly existingFolders = new Set<string>();
	readonly mkdirCalls: string[] = [];

	async exists(path: string): Promise<boolean> {
		return this.existingFolders.has(path);
	}

	async mkdir(path: string): Promise<void> {
		this.mkdirCalls.push(path);
		this.existingFolders.add(path);
	}
}

describe("ensureParentFolders", () => {
	it("creates every missing intermediate folder for a nested path, in order from the top down", async () => {
		const adapter = new FakeAdapter();

		await ensureParentFolders(adapter, "a/b/c/file.md");

		expect(adapter.mkdirCalls).toEqual(["a", "a/b", "a/b/c"]);
	});

	it("does nothing for a top-level file (no parent folder at all)", async () => {
		const adapter = new FakeAdapter();

		await ensureParentFolders(adapter, "file.md");

		expect(adapter.mkdirCalls).toEqual([]);
	});

	it("skips folders that already exist, but still creates missing ones further down the path", async () => {
		const adapter = new FakeAdapter();
		adapter.existingFolders.add("a");
		adapter.existingFolders.add("a/b");

		await ensureParentFolders(adapter, "a/b/c/file.md");

		expect(adapter.mkdirCalls).toEqual(["a/b/c"]);
	});

	it("is a no-op when the entire folder chain already exists", async () => {
		const adapter = new FakeAdapter();
		adapter.existingFolders.add("a");
		adapter.existingFolders.add("a/b");

		await ensureParentFolders(adapter, "a/b/file.md");

		expect(adapter.mkdirCalls).toEqual([]);
	});

	it("handles backslash-free, forward-slash vault-relative paths with a leading segment correctly", async () => {
		const adapter = new FakeAdapter();

		await ensureParentFolders(adapter, "notes/2026/january/entry.md");

		expect(adapter.mkdirCalls).toEqual(["notes", "notes/2026", "notes/2026/january"]);
	});
});
