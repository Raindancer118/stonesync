package de.tstieh.stonesync.sync;

import java.util.UUID;

/**
 * Notifies every client currently connected to a vault's lightweight events channel
 * ({@code /ws/vault/{vaultId}}) about a document being created or deleted, regardless of
 * whether that specific document is currently open anywhere - the per-document sync channel
 * (see {@link DocumentDeletionBroadcaster}) only reaches clients that already have that document
 * open. This is what lets a client learn "in real time" that a colleague added or removed a file
 * without needing one persistent Yjs session per file in the vault.
 *
 * <p>Kept as a narrow interface (implemented by {@code VaultEventsHandler} in
 * {@code de.tstieh.stonesync.vaultevents}) so {@link DocumentService} doesn't need to depend on
 * WebSocket transport internals.</p>
 *
 * <p>{@code originSessionId} is an opaque, client-chosen id (may be {@code null} for the
 * *ForRestore/system-triggered paths) echoed back verbatim in the broadcast, so the very client
 * that caused the change can recognize and ignore its own event client-side - the reactor can
 * then stay permanently active (including during a bulk upload/download) instead of pausing and
 * risking missing a genuine collaborator event during that window.</p>
 */
public interface VaultEventBroadcaster {

    void notifyDocumentCreated(UUID vaultId, UUID documentId, String path, DocumentEntity.ContentType contentType,
                                String originSessionId);

    void notifyDocumentDeleted(UUID vaultId, UUID documentId, String path, String originSessionId);
}
