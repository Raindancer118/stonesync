package de.tstieh.stonesync.links;

import java.util.UUID;

/**
 * Pushes a queued link rewrite to whoever currently has the affected note open, so a rename
 * visibly fixes links in real time instead of only on the next open. Implemented by the
 * vault-events channel; kept as a narrow interface so the links package does not depend on
 * WebSocket internals.
 */
public interface LinkRewriteNotifier {

    /**
     * @param path the affected note's own path - the event is only delivered to recipients who
     *             may read that note, exactly like every other vault event
     */
    void notifyLinkRewrite(UUID vaultId, UUID documentId, String path, long rewriteId, String oldLink, String newLink);
}
