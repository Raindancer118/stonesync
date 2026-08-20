package de.tstieh.stonesync.history;

import de.tstieh.stonesync.attachments.StorageProperties;
import de.tstieh.stonesync.logging.AppLog;
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
                AppLog.debug("No-op materialize for {} in vault {} - content unchanged, no commit", relativePath, vaultId);
                return false;
            }

            git.commit()
                    .setMessage("Materialized " + relativePath + " by " + authorEmail + " at " + when)
                    // The real person as the *author*, the server as the committer: this is what
                    // makes "who changed this note, and what exactly" answerable straight from
                    // the history, rather than only from a log line.
                    .setAuthor(authorEmail, authorEmail)
                    .setCommitter(SYNTHETIC_AUTHOR_NAME, SYNTHETIC_AUTHOR_EMAIL)
                    .call();
            AppLog.info("Committed {} in vault {} (by {})", relativePath, vaultId, authorEmail);
            return true;
        } catch (IOException | GitAPIException e) {
            AppLog.error("Failed to materialize {} for vault {}: {}", relativePath, vaultId, e.getMessage());
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
            AppLog.info("Removed {} from vault {}'s git history (real delete)", relativePath, vaultId);
            return true;
        } catch (GitAPIException e) {
            AppLog.error("Failed to remove {} for vault {}: {}", relativePath, vaultId, e.getMessage());
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
            AppLog.debug("Read {} log entries for vault {}", entries.size(), vaultId);
            return entries;
        } catch (org.eclipse.jgit.api.errors.NoHeadException e) {
            // A vault whose repo was opened/init'd (e.g. by an earlier no-op materialize call
            // that never actually committed) but has no commits yet - an empty history, not an
            // error.
            AppLog.debug("Vault {} has no commits yet", vaultId);
            return List.of();
        } catch (GitAPIException e) {
            AppLog.error("Failed to read git log for vault {}: {}", vaultId, e.getMessage());
            throw new VaultGitException("Failed to read git log for vault " + vaultId, e);
        }
    }

    /**
     * History of one file, newest first: who touched it, when, and the commit to diff against.
     * Follows the file across renames is deliberately NOT attempted - a StoneSync rename is a
     * metadata operation that produces a delete + add here, and pretending otherwise would make
     * the audit trail less literal than it should be.
     */
    public List<FileHistoryEntry> logForPath(UUID vaultId, String relativePath, int limit) {
        try (Git git = openOrInit(vaultId)) {
            List<FileHistoryEntry> entries = new java.util.ArrayList<>();
            for (RevCommit commit : git.log().addPath(relativePath).setMaxCount(Math.clamp(limit, 1, 500)).call()) {
                entries.add(new FileHistoryEntry(commit.getName(),
                        commit.getAuthorIdent().getEmailAddress(),
                        Instant.ofEpochSecond(commit.getCommitTime()),
                        commit.getShortMessage()));
            }
            return entries;
        } catch (org.eclipse.jgit.api.errors.NoHeadException e) {
            return List.of();
        } catch (GitAPIException e) {
            AppLog.error("Failed to read file history of {} in vault {}: {}", relativePath, vaultId, e.getMessage());
            throw new VaultGitException("Failed to read file history of " + relativePath, e);
        }
    }

    /** The unified diff one commit made to one file - the "what exactly changed" half. */
    public String diffForPath(UUID vaultId, String commitIsh, String relativePath) {
        try (Git git = openOrInit(vaultId)) {
            Repository repository = git.getRepository();
            ObjectId commitId = repository.resolve(commitIsh);
            if (commitId == null) {
                throw new CommitNotFoundException(commitIsh);
            }
            try (RevWalk revWalk = new RevWalk(repository);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                 org.eclipse.jgit.diff.DiffFormatter formatter = new org.eclipse.jgit.diff.DiffFormatter(out)) {
                RevCommit commit = revWalk.parseCommit(commitId);
                formatter.setRepository(repository);
                formatter.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(relativePath));
                RevTree parentTree = commit.getParentCount() > 0
                        ? revWalk.parseCommit(commit.getParent(0).getId()).getTree()
                        : null;
                formatter.format(parentTree == null
                                ? new org.eclipse.jgit.treewalk.EmptyTreeIterator()
                                : treeIterator(repository, parentTree),
                        treeIterator(repository, commit.getTree()));
                formatter.flush();
                return out.toString(StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            AppLog.error("Failed to diff {} at {} in vault {}: {}", relativePath, commitIsh, vaultId, e.getMessage());
            throw new VaultGitException("Failed to diff " + relativePath + " at " + commitIsh, e);
        }
    }

    private static org.eclipse.jgit.treewalk.AbstractTreeIterator treeIterator(Repository repository, RevTree tree)
            throws IOException {
        org.eclipse.jgit.treewalk.CanonicalTreeParser parser = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
        try (org.eclipse.jgit.lib.ObjectReader reader = repository.newObjectReader()) {
            parser.reset(reader, tree.getId());
        }
        return parser;
    }

    /** The full set of (path -> UTF-8 text content) at a given commit-ish. */
    public Map<String, String> readTreeAtCommit(UUID vaultId, String commitIsh) {
        try (Git git = openOrInit(vaultId)) {
            Repository repository = git.getRepository();
            ObjectId commitId = repository.resolve(commitIsh);
            if (commitId == null) {
                AppLog.warn("Restore requested for unknown commit-ish '{}' in vault {}", commitIsh, vaultId);
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
            AppLog.info("Read {} files from vault {} at commit {}", files.size(), vaultId, commitIsh);
            return files;
        } catch (IOException e) {
            AppLog.error("Failed to read tree at {} for vault {}: {}", commitIsh, vaultId, e.getMessage());
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
            AppLog.info("Initializing new git history repo for vault {} at {}", vaultId, dir);
            return Git.init().setDirectory(dir.toFile()).call();
        } catch (IOException | GitAPIException e) {
            AppLog.error("Failed to open/init git repo for vault {}: {}", vaultId, e.getMessage());
            throw new VaultGitException("Failed to open/init git repo for vault " + vaultId, e);
        }
    }

    private Path repoDir(UUID vaultId) {
        return gitRoot.resolve(vaultId.toString());
    }
}
