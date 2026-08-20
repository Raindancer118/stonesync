package de.tstieh.stonesync.sync;

import java.util.UUID;

/**
 * Told when a note is renamed, so cross-vault links pointing at it can be repaired (implemented
 * by {@code LinkRewriteService}). A narrow interface again, so the sync package does not depend
 * on the link index - and so a deployment that never uses cross-vault links pays nothing for it.
 */
public interface CrossVaultLinkMaintainer {

    int onDocumentRenamed(UUID documentId, UUID vaultId, String oldPath, String newPath);
}
