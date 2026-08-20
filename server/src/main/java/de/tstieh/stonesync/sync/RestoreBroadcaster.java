package de.tstieh.stonesync.sync;

import java.util.UUID;

/**
 * Delivers a git-restore's plaintext content to a document: immediately, if any client is
 * currently connected to it (broadcasting {@code 0x05 RESTORE_CONTENT} to every such session);
 * otherwise queued (see {@link DocumentRestoreQueueService}) for delivery right after the next
 * client's on-connect catch-up burst. Kept as a narrow interface (implemented by
 * {@link DocumentSyncHandler}) so {@code de.tstieh.stonesync.history.RestoreService} doesn't need
 * to depend on WebSocket transport internals.
 */
public interface RestoreBroadcaster {

    void broadcastOrQueueRestore(UUID documentId, String content);
}
