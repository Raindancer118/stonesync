import { describe, expect, it } from "vitest";
import type { HistoryEntry } from "../access/PermissionsClient";
import { authorsIn, displayAuthor, filterHistory, withinDateRange, EMPTY_FILTER } from "./historyFilter";

const entry = (overrides: Partial<HistoryEntry>): HistoryEntry => ({
	commitId: "abc123",
	authorEmail: "alice@example.com",
	changedAt: "2026-08-20T10:30:00Z",
	message: "Materialized Notes/a.md by alice@example.com at 2026-08-20T10:30:00Z",
	...overrides,
});

describe("note history filtering", () => {
	it("recovers the real person from older commits written under the server's own identity", () => {
		const legacy = entry({
			authorEmail: "sync@stonesync.local",
			message: "Materialized User interface/Appearance.md by tom@example.com at 2026-08-20T10:30:55Z",
		});

		expect(displayAuthor(legacy)).toBe("tom@example.com");
		expect(displayAuthor(entry({}))).toBe("alice@example.com");
	});

	it("falls back to whatever the commit says when the message names nobody", () => {
		expect(displayAuthor(entry({ authorEmail: "sync@stonesync.local", message: "Restored" })))
			.toBe("sync@stonesync.local");
	});

	it("offers every distinct person, sorted", () => {
		const entries = [entry({}), entry({ authorEmail: "bob@example.com" }), entry({})];

		expect(authorsIn(entries)).toEqual(["alice@example.com", "bob@example.com"]);
	});

	it("filters by person", () => {
		const entries = [entry({}), entry({ authorEmail: "bob@example.com" })];

		const filtered = filterHistory(entries, { ...EMPTY_FILTER, author: "bob@example.com" });

		expect(filtered).toHaveLength(1);
		expect(filtered[0].authorEmail).toBe("bob@example.com");
	});

	it("searches author and message, case-insensitively", () => {
		const entries = [entry({}), entry({ authorEmail: "bob@example.com", message: "Materialized Other/b.md" })];

		expect(filterHistory(entries, { ...EMPTY_FILTER, query: "ALICE" })).toHaveLength(1);
		expect(filterHistory(entries, { ...EMPTY_FILTER, query: "other/b" })).toHaveLength(1);
		expect(filterHistory(entries, { ...EMPTY_FILTER, query: "nothing here" })).toHaveLength(0);
	});

	// The bounds are the user's own calendar days: what they filter for has to match what the
	// list shows them, which is local time. Built from local Dates so the test holds in any zone.
	const localInstant = (year: number, month: number, day: number, hour: number, minute: number) =>
		new Date(year, month - 1, day, hour, minute).toISOString();

	it("includes the whole of the end day as the user sees it, not just its midnight", () => {
		expect(withinDateRange(localInstant(2026, 8, 20, 23, 59), "2026-08-20", "2026-08-20")).toBe(true);
		expect(withinDateRange(localInstant(2026, 8, 21, 0, 30), "2026-08-20", "2026-08-20")).toBe(false);
		expect(withinDateRange(localInstant(2026, 8, 19, 23, 0), "2026-08-20", "")).toBe(false);
		expect(withinDateRange(localInstant(2026, 8, 20, 0, 1), "2026-08-20", "")).toBe(true);
	});

	it("treats an empty bound as open-ended", () => {
		expect(withinDateRange("2020-01-01T00:00:00Z", "", "")).toBe(true);
		expect(withinDateRange("2026-08-20T10:00:00Z", "", "2026-12-31")).toBe(true);
	});

	it("combines person, date and text into one result set", () => {
		const entries = [
			entry({ authorEmail: "alice@example.com", changedAt: localInstant(2026, 8, 1, 10, 0), message: "Materialized a.md" }),
			entry({ authorEmail: "alice@example.com", changedAt: localInstant(2026, 8, 20, 10, 0), message: "Materialized b.md" }),
			entry({ authorEmail: "bob@example.com", changedAt: localInstant(2026, 8, 20, 11, 0), message: "Materialized b.md" }),
		];

		const filtered = filterHistory(entries, {
			query: "b.md",
			author: "alice@example.com",
			from: "2026-08-10",
			to: "2026-08-20",
		});

		expect(filtered).toHaveLength(1);
		expect(filtered[0].changedAt).toBe(localInstant(2026, 8, 20, 10, 0));
	});
});
