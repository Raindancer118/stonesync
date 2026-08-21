package de.tstieh.stonesync.history;

import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.audit.AuditEventType;
import de.tstieh.stonesync.audit.AuditService;
import de.tstieh.stonesync.links.LinkIndexService;
import de.tstieh.stonesync.logging.AppLog;
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
    private final AuditService auditService;
    private final LinkIndexService linkIndexService;
    private final Clock clock;

    public MaterializeService(DocumentService documentService, UserRepository userRepository,
                               VaultGitRepository gitRepository, AuditService auditService,
                               LinkIndexService linkIndexService, Clock clock) {
        this.documentService = documentService;
        this.userRepository = userRepository;
        this.gitRepository = gitRepository;
        this.auditService = auditService;
        this.linkIndexService = linkIndexService;
        this.clock = clock;
    }

    public void materialize(UUID userId, UUID documentId, String content) {
        // Write permission, not read: this is the channel through which content actually lands in
        // the vault's history, so a read-only collaborator must not be able to use it.
        DocumentService.DocumentLocation location = documentService.locateForWrite(userId, documentId);
        String authorEmail = userRepository.findById(userId).map(UserEntity::getEmail).orElse("unknown");
        // High-frequency (up to once per ~3s debounce per open file) - DEBUG only, the actual
        // commit-or-not decision is logged one level up inside VaultGitRepository.
        AppLog.debug("Materializing document {} ({}) by {}", documentId, location.path(), authorEmail);
        boolean committed = gitRepository.writeAndCommitIfChanged(location.vaultId(), location.path(), content,
                authorEmail, clock.instant());
        // Materialize is the only place the server sees plaintext, so it is also the only place
        // the cross-vault link index can be maintained - the Yjs path stays an opaque relay.
        linkIndexService.reindex(documentId, location.vaultId(), content);
        // Same reasoning for full-text search (see migration V7): keep the note's search-only
        // plaintext copy current, regardless of whether this particular debounce tick actually
        // changed the git-committed content.
        documentService.updatePlainText(documentId, content);

        if (committed) {
            // Only on a real change: the client re-materializes on a debounce timer, and an
            // unchanged note must not produce an audit entry any more than it produces a commit.
            auditService.record(AuditEventType.DOCUMENT_EDITED, userId, location.vaultId(), documentId,
                    location.path(), null, null);
        }
    }
}
