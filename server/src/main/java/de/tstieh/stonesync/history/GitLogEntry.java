package de.tstieh.stonesync.history;

import java.time.Instant;

public record GitLogEntry(String commitId, String message, Instant committedAt) {
}
