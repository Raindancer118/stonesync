package de.tstieh.stonesync.sync;

import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.admin.VaultAccessService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository repository;

    @Mock
    private VaultAccessService vaultAccessService;

    private DocumentService service;
    private final UUID documentId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new DocumentService(repository, vaultAccessService, clock);
    }

    @Test
    @DisplayName("renaming only changes current_path - the document UUID and the Yjs content remain untouched")
    void renameOnlyChangesCurrentPath() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "old/path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));

        service.rename(userId, documentId, "new/path.md");

        assertThat(doc.getId()).isEqualTo(documentId);
        assertThat(doc.getCurrentPath()).isEqualTo("new/path.md");
    }

    @Test
    @DisplayName("renaming an unknown document fails")
    void renameUnknownDocumentThrows() {
        when(repository.findById(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename(userId, documentId, "x"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    @DisplayName("renaming without vault access fails with VaultAccessDeniedException (IDOR protection)")
    void renameWithoutVaultAccessIsDenied() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "old/path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService).requireAccess(userId, vaultId);

        assertThatThrownBy(() -> service.rename(userId, documentId, "new/path.md"))
                .isInstanceOf(VaultAccessDeniedException.class);
        assertThat(doc.getCurrentPath()).isEqualTo("old/path.md");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("deleting only sets deleted_at (tombstone) - the record remains instead of being deleted")
    void deleteSetsTombstoneInsteadOfRemovingRow() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));

        service.markDeleted(userId, documentId);

        assertThat(doc.isDeleted()).isTrue();
        assertThat(doc.getDeletedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("deleting without vault access fails with VaultAccessDeniedException (IDOR protection)")
    void deleteWithoutVaultAccessIsDenied() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService).requireAccess(userId, vaultId);

        assertThatThrownBy(() -> service.markDeleted(userId, documentId))
                .isInstanceOf(VaultAccessDeniedException.class);
        assertThat(doc.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("resolving a known (vaultId, path) returns the existing UUID without creating a new document")
    void resolveReturnsExistingDocumentId() {
        DocumentEntity existing = new DocumentEntity(documentId, vaultId, "notes/a.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findByVaultIdAndCurrentPath(vaultId, "notes/a.md")).thenReturn(Optional.of(existing));

        UUID resolved = service.resolveOrCreate(userId, vaultId, "notes/a.md", DocumentEntity.ContentType.TEXT);

        assertThat(resolved).isEqualTo(documentId);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("resolving an unknown (vaultId, path) creates a new document with a fresh UUID")
    void resolveCreatesNewDocumentWhenUnknown() {
        when(repository.findByVaultIdAndCurrentPath(vaultId, "notes/new.md")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UUID resolved = service.resolveOrCreate(userId, vaultId, "notes/new.md", DocumentEntity.ContentType.TEXT);

        assertThat(resolved).isNotNull();
        org.mockito.ArgumentCaptor<DocumentEntity> captor = org.mockito.ArgumentCaptor.forClass(DocumentEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(resolved);
        assertThat(captor.getValue().getVaultId()).isEqualTo(vaultId);
        assertThat(captor.getValue().getCurrentPath()).isEqualTo("notes/new.md");
        assertThat(captor.getValue().getContentType()).isEqualTo(DocumentEntity.ContentType.TEXT);
    }

    @Test
    @DisplayName("resolving without vault access fails before any document is ever read or created (IDOR protection)")
    void resolveWithoutVaultAccessIsDenied() {
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService).requireAccess(userId, vaultId);

        assertThatThrownBy(() -> service.resolveOrCreate(userId, vaultId, "notes/new.md", DocumentEntity.ContentType.TEXT))
                .isInstanceOf(VaultAccessDeniedException.class);

        verify(repository, never()).findByVaultIdAndCurrentPath(any(), any());
        verify(repository, never()).save(any());
    }
}
