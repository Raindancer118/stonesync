package de.tstieh.stonesync.history;

import de.tstieh.stonesync.attachments.StorageProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A real, working-tree git repository per vault at {@code ${stonesync.storage.path}/_git/{vaultId}},
 * used purely as a durability/history layer (see {@code MaterializeService}/{@code RestoreService}) -
 * clients never see git at all, and the "dumb relay" Yjs sync path is completely untouched by any
 * of this. Pure-Java (JGit), so no native git binary is required on the server.
 */
@Component
public class VaultGitRepository {

    private static final String SYNTHETIC_AUTHOR_NAME = "StoneSync";
    private static final String SYNTHETIC_AUTHOR_EMAIL = "sync@stonesync.local";

    private final Path gitRoot;
    /**
     * JGit serializes concurrent writers to the same repo at the filesystem level via
     * {@code .git/index.lock} - without an in-process lock too, two colleagues editing different
     * files of the same vault at once would race their debounced materialize calls into that
     * same lock file, and the loser's write would throw rather than simply wait its turn (found
     * via agy architecture review). One lock per vault, not a single global lock, so unrelated
     * vaults never block each other.
     */
    private final ConcurrentHashMap<UUID, Lock> vaultLocks = new ConcurrentHashMap<>();

    public VaultGitRepository(StorageProperties storageProperties) {
        this.gitRoot = Path.of(storageProperties.path()).resolve("_git");
    }

    /**
     * Writes {@code content} at {@code relativePath} inside the vault's repo and commits only if
     * the content actually changed (checked via {@code git status} first) - a debounced-but-
     * unedited materialize call must never create an empty commit. Returns whether a commit was
     * made.
     */
    public boolean writeAndCommitIfChanged(UUID vaultId, String relativePath, String content,
                                            String authorEmail, Instant when) {
        Lock lock = lockFor(vaultId);
        lock.lock();
        try (Git git = openOrInit(vaultId)) {
            Path repoDir = repoDir(vaultId);
            Path target = resolveInRepo(repoDir, relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);

            git.add().addFilepattern(relativePath).call();
            Status status = git.status().call();
            if (status.isClean()) {
                return false;
            }

            git.commit()
                    .setMessage("Materialized " + relativePath + " by " + authorEmail + " at " + when)
                    .setAuthor(SYNTHETIC_AUTHOR_NAME, SYNTHETIC_AUTHOR_EMAIL)
                    .call();
            return true;
        } catch (IOException | GitAPIException e) {
            throw new VaultGitException("Failed to materialize " + relativePath + " for vault " + vaultId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes {@code relativePath} from the vault's repo and commits, if the file is currently
     * tracked (a no-op otherwise - materialize was never called for it, or it was already
     * removed). Called on a real user-initiated document delete (NOT during a restore's
     * tombstoning of documents absent from the target commit - that would corrupt the very
     * history being restored from by rewriting it). Without this, a deleted document's last
     * materialized content stays in the git tree forever and gets resurrected by any future
     * restore (found via agy architecture review).
     */
    public boolean removeAndCommitIfPresent(UUID vaultId, String relativePath, Instant when) {
        Lock lock = lockFor(vaultId);
        lock.lock();
        try (Git git = openOrInit(vaultId)) {
            Path repoDir = repoDir(vaultId);
            Path target = resolveInRepo(repoDir, relativePath);
            if (!Files.exists(target)) {
                return false;
            }

            git.rm().addFilepattern(relativePath).call();
            Status status = git.status().call();
            if (status.isClean()) {
                return false;
            }

            git.commit()
                    .setMessage("Deleted " + relativePath + " at " + when)
                    .setAuthor(SYNTHETIC_AUTHOR_NAME, SYNTHETIC_AUTHOR_EMAIL)
                    .call();
            return true;
        } catch (GitAPIException e) {
            throw new VaultGitException("Failed to remove " + relativePath + " for vault " + vaultId, e);
        } finally {
            lock.unlock();
        }
    }

    /** Commit history, newest first. */
    public List<GitLogEntry> log(UUID vaultId) {
        try (Git git = openOrInit(vaultId)) {
            List<GitLogEntry> entries = new java.util.ArrayList<>();
            for (RevCommit commit : git.log().call()) {
                entries.add(new GitLogEntry(commit.getName(), commit.getShortMessage(),
                        Instant.ofEpochSecond(commit.getCommitTime())));
            }
            return entries;
        } catch (org.eclipse.jgit.api.errors.NoHeadException e) {
            // A vault whose repo was opened/init'd (e.g. by an earlier no-op materialize call
            // that never actually committed) but has no commits yet - an empty history, not an
            // error.
            return List.of();
        } catch (GitAPIException e) {
            throw new VaultGitException("Failed to read git log for vault " + vaultId, e);
        }
    }

    /** The full set of (path -> UTF-8 text content) at a given commit-ish. */
    public Map<String, String> readTreeAtCommit(UUID vaultId, String commitIsh) {
        try (Git git = openOrInit(vaultId)) {
            Repository repository = git.getRepository();
            ObjectId commitId = repository.resolve(commitIsh);
            if (commitId == null) {
                throw new CommitNotFoundException(commitIsh);
            }

            Map<String, String> files = new LinkedHashMap<>();
            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(commitId);
                RevTree tree = commit.getTree();
                try (TreeWalk treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(tree);
                    treeWalk.setRecursive(true);
                    while (treeWalk.next()) {
                        ObjectId objectId = treeWalk.getObjectId(0);
                        ObjectLoader loader = repository.open(objectId);
                        files.put(treeWalk.getPathString(), new String(loader.getBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            return files;
        } catch (IOException e) {
            throw new VaultGitException("Failed to read tree at " + commitIsh + " for vault " + vaultId, e);
        }
    }

    private Path resolveInRepo(Path repoDir, String relativePath) {
        Path target = repoDir.resolve(relativePath).normalize();
        if (!target.startsWith(repoDir)) {
            throw new IllegalArgumentException("Rejected path outside the vault git root: " + relativePath);
        }
        return target;
    }

    private Lock lockFor(UUID vaultId) {
        return vaultLocks.computeIfAbsent(vaultId, id -> new ReentrantLock());
    }

    private Git openOrInit(UUID vaultId) {
        try {
            Path dir = repoDir(vaultId);
            Files.createDirectories(dir);
            if (Files.exists(dir.resolve(".git"))) {
                return Git.open(dir.toFile());
            }
            return Git.init().setDirectory(dir.toFile()).call();
        } catch (IOException | GitAPIException e) {
            throw new VaultGitException("Failed to open/init git repo for vault " + vaultId, e);
        }
    }

    private Path repoDir(UUID vaultId) {
        return gitRoot.resolve(vaultId.toString());
    }
}
