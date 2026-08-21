import { describe, it, expect } from "vitest";
import { pathsRemovedSincePreviousSnapshot } from "./reconcileRemovals";

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
