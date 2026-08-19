package de.tstieh.stonesync.sync;

import java.util.UUID;

/**
 * Notifies currently-connected sync sessions that a document was deleted, so other open
 * clients react immediately instead of only learning about it on their next reconnect.
 * Kept as a narrow interface (implemented by {@link DocumentSyncHandler}) so
 * {@link DocumentService} doesn't need to depend on WebSocket transport internals.
 */
public interface DocumentDeletionBroadcaster {

    void broadcastDeleteNotice(UUID documentId);
}
