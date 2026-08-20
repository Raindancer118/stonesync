package de.tstieh.stonesync.history;

import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.sync.DocumentService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * The client-voluntary "materialize" side-channel that backs the git history: decoupled entirely
 * from the Yjs sync path (see {@code DocumentSession}'s debounced push after each edit) so the
 * server never has to decode CRDT bytes to build history - the client is the only party that
 * understands Yjs, and hands over already-decoded plaintext here.
 */
@Service
public class MaterializeService {

    private final DocumentService documentService;
    private final UserRepository userRepository;
    private final VaultGitRepository gitRepository;
    private final Clock clock;

    public MaterializeService(DocumentService documentService, UserRepository userRepository,
                               VaultGitRepository gitRepository, Clock clock) {
        this.documentService = documentService;
        this.userRepository = userRepository;
        this.gitRepository = gitRepository;
        this.clock = clock;
    }

    public void materialize(UUID userId, UUID documentId, String content) {
        DocumentService.DocumentLocation location = documentService.locate(userId, documentId);
        String authorEmail = userRepository.findById(userId).map(UserEntity::getEmail).orElse("unknown");
        gitRepository.writeAndCommitIfChanged(location.vaultId(), location.path(), content, authorEmail, clock.instant());
    }
}
