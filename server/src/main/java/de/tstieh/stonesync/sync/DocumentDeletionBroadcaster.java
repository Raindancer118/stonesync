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

    /**
     * Force-closes every currently connected sync session for a document, after telling each
     * one {@code DELETE_NOTICE} - used only when a document is about to be hard-deleted (see
     * {@code AdminService#deleteVault(UUID, boolean)}'s force path), where any further update a
     * still-connected client sends for it must not be allowed to land: a hard delete removes the
     * document row and everything referencing it in one go, so a write arriving mid-operation
     * would either be silently lost or (if it landed just before the row delete) cause that
     * delete to fail its foreign key check. Ordinary single-document deletes leave sessions open
     * (see {@link #broadcastDeleteNotice}) since there the client is expected to react to the
     * notice itself; this is deliberately more forceful.
     */
    void kickSessions(UUID documentId);
}
