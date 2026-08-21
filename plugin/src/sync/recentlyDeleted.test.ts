import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { markRecentlyDeleted, wasRecentlyDeleted } from "./recentlyDeleted";

describe("recentlyDeleted", () => {
	beforeEach(() => {
		vi.useFakeTimers();
	});

	afterEach(() => {
		vi.useRealTimers();
	});

	it("reports a path as recently deleted right after marking it", () => {
		markRecentlyDeleted("Notes/gone.md");

		expect(wasRecentlyDeleted("Notes/gone.md")).toBe(true);
	});

	it("reports a path that was never marked as not recently deleted", () => {
		expect(wasRecentlyDeleted("Notes/never-touched.md")).toBe(false);
	});

	it("stops reporting a path as recently deleted once the TTL has passed", () => {
		markRecentlyDeleted("Notes/gone.md");

		vi.advanceTimersByTime(30 * 60_000 + 1_000);

		expect(wasRecentlyDeleted("Notes/gone.md")).toBe(false);
	});

	it("still reports it just before the TTL expires", () => {
		markRecentlyDeleted("Notes/gone.md");

		vi.advanceTimersByTime(30 * 60_000 - 1_000);

		expect(wasRecentlyDeleted("Notes/gone.md")).toBe(true);
	});

	it("tracks paths independently", () => {
		markRecentlyDeleted("Notes/a.md");

		expect(wasRecentlyDeleted("Notes/a.md")).toBe(true);
		expect(wasRecentlyDeleted("Notes/b.md")).toBe(false);
	});
});
