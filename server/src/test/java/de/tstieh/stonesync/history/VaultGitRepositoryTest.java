package de.tstieh.stonesync.history;

import de.tstieh.stonesync.attachments.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises a real, on-disk JGit repository - no mocking, since the whole point is I/O + git plumbing. */
class VaultGitRepositoryTest {

    private VaultGitRepository repository;
    private final UUID vaultId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        repository = new VaultGitRepository(new StorageProperties(tempDir.toString()));
    }

    @Test
    @DisplayName("first materialize of a new file creates a commit")
    void firstMaterializeCreatesCommit() {
        boolean committed = repository.writeAndCommitIfChanged(vaultId, "notes/first.md", "hello world",
                "tom@example.com", now);

        assertThat(committed).isTrue();
        assertThat(repository.log(vaultId)).hasSize(1);
    }

    @Test
    @DisplayName("materializing identical content again does not create a second commit")
    void materializingUnchangedContentDoesNotCommitAgain() {
        repository.writeAndCommitIfChanged(vaultId, "notes/first.md", "hello world", "tom@example.com", now);
        boolean secondCommitted = repository.writeAndCommitIfChanged(vaultId, "notes/first.md", "hello world",
                "tom@example.com", now.plusSeconds(5));

        assertThat(secondCommitted).isFalse();
        assertThat(repository.log(vaultId)).hasSize(1);
    }

    @Test
    @DisplayName("materializing changed content creates a second commit")
    void materializingChangedContentCommitsAgain() {
        repository.writeAndCommitIfChanged(vaultId, "notes/first.md", "hello world", "tom@example.com", now);
        boolean secondCommitted = repository.writeAndCommitIfChanged(vaultId, "notes/first.md", "hello there",
                "tom@example.com", now.plusSeconds(5));

        assertThat(secondCommitted).isTrue();
        assertThat(repository.log(vaultId)).hasSize(2);
    }

    @Test
    @DisplayName("readTreeAtCommit returns the exact file content at that commit")
    void readTreeAtCommitReturnsContent() {
        repository.writeAndCommitIfChanged(vaultId, "notes/a.md", "version one", "tom@example.com", now);
        String firstCommitId = repository.log(vaultId).get(0).commitId();
        repository.writeAndCommitIfChanged(vaultId, "notes/a.md", "version two", "tom@example.com", now.plusSeconds(5));

        Map<String, String> atFirstCommit = repository.readTreeAtCommit(vaultId, firstCommitId);
        Map<String, String> atHead = repository.readTreeAtCommit(vaultId, "HEAD");

        assertThat(atFirstCommit).containsEntry("notes/a.md", "version one");
        assertThat(atHead).containsEntry("notes/a.md", "version two");
    }

    @Test
    @DisplayName("readTreeAtCommit reflects multiple files")
    void readTreeAtCommitReflectsMultipleFiles() {
        repository.writeAndCommitIfChanged(vaultId, "a.md", "content a", "tom@example.com", now);
        repository.writeAndCommitIfChanged(vaultId, "folder/b.md", "content b", "tom@example.com", now.plusSeconds(1));

        Map<String, String> atHead = repository.readTreeAtCommit(vaultId, "HEAD");

        assertThat(atHead).containsExactlyInAnyOrderEntriesOf(Map.of(
                "a.md", "content a",
                "folder/b.md", "content b"));
    }

    @Test
    @DisplayName("resolving an unknown commit-ish throws CommitNotFoundException")
    void unknownCommitIshThrows() {
        repository.writeAndCommitIfChanged(vaultId, "a.md", "content", "tom@example.com", now);

        assertThatThrownBy(() -> repository.readTreeAtCommit(vaultId, "not-a-real-commit"))
                .isInstanceOf(CommitNotFoundException.class);
    }

    @Test
    @DisplayName("separate vaults get separate, independent git repositories")
    void separateVaultsAreIndependent() {
        UUID otherVaultId = UUID.randomUUID();
        repository.writeAndCommitIfChanged(vaultId, "a.md", "vault one content", "tom@example.com", now);
        repository.writeAndCommitIfChanged(otherVaultId, "a.md", "vault two content", "tom@example.com", now);

        assertThat(repository.readTreeAtCommit(vaultId, "HEAD")).containsEntry("a.md", "vault one content");
        assertThat(repository.readTreeAtCommit(otherVaultId, "HEAD")).containsEntry("a.md", "vault two content");
        assertThat(repository.log(vaultId)).hasSize(1);
        assertThat(repository.log(otherVaultId)).hasSize(1);
    }

    @Test
    @DisplayName("log returns commits newest first")
    void logReturnsNewestFirst() {
        repository.writeAndCommitIfChanged(vaultId, "a.md", "one", "tom@example.com", now);
        repository.writeAndCommitIfChanged(vaultId, "a.md", "two", "tom@example.com", now.plusSeconds(1));

        List<GitLogEntry> log = repository.log(vaultId);

        assertThat(log).hasSize(2);
        assertThat(log.get(0).message()).contains("by tom@example.com");
    }

    @Test
    @DisplayName("removing a tracked file commits and it disappears from the tree at HEAD")
    void removeAndCommitIfPresentRemovesTrackedFile() {
        repository.writeAndCommitIfChanged(vaultId, "a.md", "content", "tom@example.com", now);

        boolean committed = repository.removeAndCommitIfPresent(vaultId, "a.md", now.plusSeconds(1));

        assertThat(committed).isTrue();
        assertThat(repository.readTreeAtCommit(vaultId, "HEAD")).doesNotContainKey("a.md");
        assertThat(repository.log(vaultId)).hasSize(2);
    }

    @Test
    @DisplayName("removing a file that was never materialized is a no-op, no commit created")
    void removeAndCommitIfPresentIsNoOpForUntrackedFile() {
        boolean committed = repository.removeAndCommitIfPresent(vaultId, "never-existed.md", now);

        assertThat(committed).isFalse();
        assertThat(repository.log(vaultId)).isEmpty();
    }

    @Test
    @DisplayName("a document deleted after a restore-relevant history no longer resurfaces after removal, even at a later restore read")
    void removedFileDoesNotReappearAtLaterCommit() {
        repository.writeAndCommitIfChanged(vaultId, "a.md", "content", "tom@example.com", now);
        repository.removeAndCommitIfPresent(vaultId, "a.md", now.plusSeconds(1));
        repository.writeAndCommitIfChanged(vaultId, "b.md", "other content", "tom@example.com", now.plusSeconds(2));

        assertThat(repository.readTreeAtCommit(vaultId, "HEAD")).doesNotContainKey("a.md");
    }

    @Test
    @DisplayName("concurrent materialize calls for different files of the SAME vault do not throw, and both commits land")
    void concurrentWritesToTheSameVaultDoNotThrowOrLoseCommits() throws Exception {
        int fileCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(fileCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = IntStream.range(0, fileCount)
                    .mapToObj(i -> pool.submit(() -> {
                        startLatch.await();
                        return repository.writeAndCommitIfChanged(vaultId, "file" + i + ".md",
                                "content " + i, "tom@example.com", now);
                    }))
                    .toList();
            startLatch.countDown();

            for (Future<Boolean> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(repository.log(vaultId)).hasSize(fileCount);
        Map<String, String> atHead = repository.readTreeAtCommit(vaultId, "HEAD");
        for (int i = 0; i < fileCount; i++) {
            assertThat(atHead).containsEntry("file" + i + ".md", "content " + i);
        }
    }
}
