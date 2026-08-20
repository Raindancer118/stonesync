package de.tstieh.stonesync.history;

import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentService;
import de.tstieh.stonesync.sync.RestoreBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestoreServiceTest {

    @Mock
    private VaultGitRepository gitRepository;

    @Mock
    private DocumentService documentService;

    @Mock
    private RestoreBroadcaster broadcaster;

    private RestoreService service;
    private final UUID vaultId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RestoreService(gitRepository, documentService, broadcaster);
    }

    @Test
    @DisplayName("every file in the target commit is resolved and its content broadcast/queued")
    void restoresEveryFileInTargetCommit() {
        UUID docAId = UUID.randomUUID();
        UUID docBId = UUID.randomUUID();
        when(gitRepository.readTreeAtCommit(vaultId, "abc123")).thenReturn(Map.of(
                "a.md", "content a",
                "b.md", "content b"));
        when(documentService.resolveOrCreateForRestore(vaultId, "a.md", DocumentEntity.ContentType.TEXT)).thenReturn(docAId);
        when(documentService.resolveOrCreateForRestore(vaultId, "b.md", DocumentEntity.ContentType.TEXT)).thenReturn(docBId);
        when(documentService.listNonDeletedForRestore(vaultId)).thenReturn(List.of());

        RestoreService.RestoreResult result = service.restore(vaultId, "abc123");

        verify(broadcaster).broadcastOrQueueRestore(docAId, "content a");
        verify(broadcaster).broadcastOrQueueRestore(docBId, "content b");
        assertThat(result.restoredDocumentCount()).isEqualTo(2);
        assertThat(result.tombstonedDocumentCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("a currently-existing document whose path is absent from the target commit gets tombstoned")
    void tombstonesDocumentsMissingFromTargetCommit() {
        UUID staleDocId = UUID.randomUUID();
        when(gitRepository.readTreeAtCommit(vaultId, "abc123")).thenReturn(Map.of("a.md", "content a"));
        when(documentService.resolveOrCreateForRestore(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(documentService.listNonDeletedForRestore(vaultId)).thenReturn(List.of(
                new DocumentService.DocumentSummary(staleDocId, "deleted-in-target.md", DocumentEntity.ContentType.TEXT)));

        RestoreService.RestoreResult result = service.restore(vaultId, "abc123");

        verify(documentService).markDeletedForRestore(staleDocId);
        assertThat(result.tombstonedDocumentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a currently-existing document whose path IS present in the target commit is NOT tombstoned")
    void doesNotTombstoneDocumentsStillPresentInTargetCommit() {
        UUID docId = UUID.randomUUID();
        when(gitRepository.readTreeAtCommit(vaultId, "abc123")).thenReturn(Map.of("a.md", "content a"));
        when(documentService.resolveOrCreateForRestore(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(documentService.listNonDeletedForRestore(vaultId)).thenReturn(List.of(
                new DocumentService.DocumentSummary(docId, "a.md", DocumentEntity.ContentType.TEXT)));

        service.restore(vaultId, "abc123");

        verify(documentService, never()).markDeletedForRestore(any());
    }
}
