import { describe, it, expect } from "vitest";
import { pathsRemovedSincePreviousSnapshot, isPlausibleRemovalCount } from "./reconcileRemovals";

describe("pathsRemovedSincePreviousSnapshot", () => {
	it("returns nothing when there is no prior snapshot - unsafe to infer a deletion", () => {
		const removed = pathsRemovedSincePreviousSnapshot(undefined, ["Notes/a.md", "Notes/b.md"]);

		expect(removed).toEqual([]);
	});

	it("detects a path present before but missing now as removed", () => {
		const removed = pathsRemovedSincePreviousSnapshot(
			["Notes/a.md", "Notes/b.md"],
			["Notes/a.md"]
		);

		expect(removed).toEqual(["Notes/b.md"]);
	});

	it("detects multiple removed paths at once (the reported real scenario: ~10 deleted while offline)", () => {
		const previouslyKnown = Array.from({ length: 10 }, (_, i) => `Notes/${i}.md`);
		const current: string[] = [];

		const removed = pathsRemovedSincePreviousSnapshot(previouslyKnown, current);

		expect(removed).toHaveLength(10);
		expect(removed).toEqual(previouslyKnown);
	});

	it("returns nothing when nothing changed", () => {
		const removed = pathsRemovedSincePreviousSnapshot(["Notes/a.md"], ["Notes/a.md"]);

		expect(removed).toEqual([]);
	});

	it("does not report a newly added path as removed", () => {
		const removed = pathsRemovedSincePreviousSnapshot(["Notes/a.md"], ["Notes/a.md", "Notes/new.md"]);

		expect(removed).toEqual([]);
	});
});

describe("isPlausibleRemovalCount", () => {
	it("trusts a small removal - a genuine handful of files deleted while offline", () => {
		expect(isPlausibleRemovalCount(700, 10)).toBe(true);
	});

	it("trusts removing exactly half", () => {
		expect(isPlausibleRemovalCount(100, 50)).toBe(true);
	});

	it("rejects removing more than half - the live-incident scenario (107 of ~700 removed was fine; " +
		"far more than half vanishing at once is the red flag)", () => {
		expect(isPlausibleRemovalCount(700, 351)).toBe(false);
	});

	it("rejects a total wipeout even when the previous snapshot was small", () => {
		expect(isPlausibleRemovalCount(3, 3)).toBe(false);
	});

	it("trusts a zero-removal reconcile regardless of vault size", () => {
		expect(isPlausibleRemovalCount(700, 0)).toBe(true);
	});

	it("trusts anything when there was no previous snapshot to lose confidence in (nothing known yet)", () => {
		expect(isPlausibleRemovalCount(0, 0)).toBe(true);
	});
});
