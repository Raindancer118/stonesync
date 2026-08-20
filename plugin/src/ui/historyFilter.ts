import type { HistoryEntry } from "../access/PermissionsClient";

/**
 * Filtering for the note-history dialog. Kept as pure functions so the (fiddly) date-boundary and
 * matching rules are testable without a DOM.
 */
export interface HistoryFilter {
	/** Free text, matched against author and commit message. Case-insensitive. */
	query: string;
	/** Exact author email, or "" for everyone. */
	author: string;
	/** ISO date (YYYY-MM-DD), inclusive. */
	from: string;
	/** ISO date (YYYY-MM-DD), inclusive - the whole day counts, not midnight. */
	to: string;
}

export const EMPTY_FILTER: HistoryFilter = { query: "", author: "", from: "", to: "" };

/**
 * Commits written before StoneSync recorded the real author carry the server's own identity, with
 * the actual person named in the commit message ("Materialized X by someone@example.com at ...").
 * Rewriting that history would be dishonest, so the person is recovered for display instead.
 */
const SYNTHETIC_AUTHOR = "sync@stonesync.local";
const AUTHOR_IN_MESSAGE = / by (\S+@\S+) at /;

export function displayAuthor(entry: HistoryEntry): string {
	if (entry.authorEmail && entry.authorEmail !== SYNTHETIC_AUTHOR) {
		return entry.authorEmail;
	}
	const match = AUTHOR_IN_MESSAGE.exec(entry.message ?? "");
	return match ? match[1] : entry.authorEmail;
}

/** Every distinct person in this history, ready for the "person" dropdown. */
export function authorsIn(entries: HistoryEntry[]): string[] {
	return [...new Set(entries.map(displayAuthor))].sort((a, b) => a.localeCompare(b));
}

export function filterHistory(entries: HistoryEntry[], filter: HistoryFilter): HistoryEntry[] {
	const query = filter.query.trim().toLowerCase();
	return entries.filter((entry) => {
		const author = displayAuthor(entry);
		if (filter.author && author !== filter.author) return false;
		if (!withinDateRange(entry.changedAt, filter.from, filter.to)) return false;
		if (!query) return true;
		return author.toLowerCase().includes(query) || (entry.message ?? "").toLowerCase().includes(query);
	});
}

/** Inclusive on both ends; an empty bound means "open". */
export function withinDateRange(isoTimestamp: string, from: string, to: string): boolean {
	const changed = new Date(isoTimestamp);
	if (Number.isNaN(changed.getTime())) return true;
	if (from) {
		const start = new Date(`${from}T00:00:00`);
		if (!Number.isNaN(start.getTime()) && changed < start) return false;
	}
	if (to) {
		// The whole "to" day belongs to the range - a filter of 20.8. to 20.8. must show that day.
		const end = new Date(`${to}T00:00:00`);
		if (!Number.isNaN(end.getTime())) {
			end.setDate(end.getDate() + 1);
			if (changed >= end) return false;
		}
	}
	return true;
}
