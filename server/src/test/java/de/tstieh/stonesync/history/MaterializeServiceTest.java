package de.tstieh.stonesync.history;

import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.sync.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterializeServiceTest {

    @Mock
    private DocumentService documentService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private VaultGitRepository gitRepository;

    private MaterializeService service;
    @org.mockito.Mock
    private de.tstieh.stonesync.audit.AuditService auditService;

    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        service = new MaterializeService(documentService, userRepository, gitRepository, auditService,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("materialize resolves the document's vault/path and writes+commits it under the author's email")
    void materializeWritesAndCommits() {
        when(documentService.locateForWrite(userId, documentId))
                .thenReturn(new DocumentService.DocumentLocation(vaultId, "notes/a.md"));
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(new UserEntity(userId, "tom@example.com", "hash", now)));

        service.materialize(userId, documentId, "the current content");

        verify(gitRepository).writeAndCommitIfChanged(vaultId, "notes/a.md", "the current content",
                "tom@example.com", now);
    }

    @Test
    @DisplayName("materialize falls back to a placeholder author when the user can't be found")
    void materializeFallsBackToUnknownAuthor() {
        when(documentService.locateForWrite(userId, documentId))
                .thenReturn(new DocumentService.DocumentLocation(vaultId, "notes/a.md"));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        service.materialize(userId, documentId, "content");

        verify(gitRepository).writeAndCommitIfChanged(vaultId, "notes/a.md", "content", "unknown", now);
    }
}
