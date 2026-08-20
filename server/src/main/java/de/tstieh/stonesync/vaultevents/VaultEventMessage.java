package de.tstieh.stonesync.vaultevents;

/**
 * JSON message sent over the vault-events channel. {@code contentType} is only present for
 * {@code document_created} (null for {@code document_deleted}) - Jackson's default of omitting
 * null fields is NOT configured globally here, so the client always sees the key, just possibly
 * {@code null}, which is simpler to parse than a conditionally-present field.
 *
 * <p>{@code originSessionId} lets the very client that caused the change recognize and ignore
 * its own event (see {@code de.tstieh.stonesync.sync.VaultEventBroadcaster}); {@code null} when
 * the change wasn't attributable to a specific plugin instance (e.g. a restore).</p>
 */
public record VaultEventMessage(String type, String documentId, String path, String contentType, String originSessionId) {

    public static final String TYPE_DOCUMENT_CREATED = "document_created";
    public static final String TYPE_DOCUMENT_DELETED = "document_deleted";
    /**
     * A note the recipient could read until a moment ago is no longer theirs to see (their role
     * changed, or a path rule now excludes them). Sent only to the affected user's own sessions -
     * the client removes its local copy, so revoking access actually takes the content off the
     * device instead of just stopping future updates.
     */
    public static final String TYPE_ACCESS_REVOKED = "access_revoked";
}
