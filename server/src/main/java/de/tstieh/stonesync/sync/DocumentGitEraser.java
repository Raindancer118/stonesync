package de.tstieh.stonesync.sync;

import java.util.UUID;

/**
 * Removes a document's materialized content from the vault's git history on a real,
 * user-initiated delete (see {@code DocumentService#markDeleted}) - without this, a deleted
 * document's last materialized content stays in the git tree forever and gets resurrected by
 * any future restore (found via agy architecture review). Deliberately NOT called for
 * {@code markDeletedForRestore}: tombstoning documents absent from a restore's target commit
 * mirrors an older state, it is not a new deletion event, and committing a "removal" into the
 * git history at that point would corrupt the very history being restored from.
 *
 * <p>Kept as a narrow interface (implemented by an adapter in
 * {@code de.tstieh.stonesync.history}) so {@link DocumentService} doesn't need to depend on
 * JGit/git internals.</p>
 */
public interface DocumentGitEraser {

    void removeFromGit(UUID vaultId, String path);
}
