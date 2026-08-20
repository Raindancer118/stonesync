package de.tstieh.stonesync.history;

import java.time.Instant;

/** One change to one note: who wrote it, when, and the commit id to fetch the diff for. */
public record FileHistoryEntry(String commitId, String authorEmail, Instant changedAt, String message) {
}
