package de.tstieh.stonesync.history;

import de.tstieh.stonesync.sync.DocumentGitEraser;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/** Wires {@link DocumentGitEraser} (a {@code sync}-package interface) to {@link VaultGitRepository}. */
@Component
public class GitEraserAdapter implements DocumentGitEraser {

    private final VaultGitRepository gitRepository;
    private final Clock clock;

    public GitEraserAdapter(VaultGitRepository gitRepository, Clock clock) {
        this.gitRepository = gitRepository;
        this.clock = clock;
    }

    @Override
    public void removeFromGit(UUID vaultId, String path) {
        gitRepository.removeAndCommitIfPresent(vaultId, path, clock.instant());
    }
}
