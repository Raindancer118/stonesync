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
}
