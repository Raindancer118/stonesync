package de.tstieh.stonesync.sync;

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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository repository;

    private DocumentService service;
    private final UUID documentId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new DocumentService(repository, clock);
    }

    @Test
    @DisplayName("Umbenennen aendert nur current_path - die Dokument-UUID und der Yjs-Content bleiben unangetastet")
    void renameOnlyChangesCurrentPath() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "old/path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));

        service.rename(documentId, "new/path.md");

        assertThat(doc.getId()).isEqualTo(documentId);
        assertThat(doc.getCurrentPath()).isEqualTo("new/path.md");
    }

    @Test
    @DisplayName("Umbenennen eines unbekannten Dokuments schlaegt fehl")
    void renameUnknownDocumentThrows() {
        when(repository.findById(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename(documentId, "x"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    @DisplayName("Loeschen setzt nur deleted_at (Tombstone) - der Datensatz bleibt bestehen statt geloescht zu werden")
    void deleteSetsTombstoneInsteadOfRemovingRow() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));

        service.markDeleted(documentId);

        assertThat(doc.isDeleted()).isTrue();
        assertThat(doc.getDeletedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("Resolve eines bekannten (vaultId, path) liefert die bestehende UUID, ohne ein neues Dokument anzulegen")
    void resolveReturnsExistingDocumentId() {
        DocumentEntity existing = new DocumentEntity(documentId, vaultId, "notes/a.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findByVaultIdAndCurrentPath(vaultId, "notes/a.md")).thenReturn(Optional.of(existing));

        UUID resolved = service.resolveOrCreate(vaultId, "notes/a.md", DocumentEntity.ContentType.TEXT);

        assertThat(resolved).isEqualTo(documentId);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("Resolve eines unbekannten (vaultId, path) legt ein neues Dokument mit frischer UUID an")
    void resolveCreatesNewDocumentWhenUnknown() {
        when(repository.findByVaultIdAndCurrentPath(vaultId, "notes/new.md")).thenReturn(Optional.empty());
        when(repository.save(org.mockito.Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));

        UUID resolved = service.resolveOrCreate(vaultId, "notes/new.md", DocumentEntity.ContentType.TEXT);

        assertThat(resolved).isNotNull();
        org.mockito.ArgumentCaptor<DocumentEntity> captor = org.mockito.ArgumentCaptor.forClass(DocumentEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(resolved);
        assertThat(captor.getValue().getVaultId()).isEqualTo(vaultId);
        assertThat(captor.getValue().getCurrentPath()).isEqualTo("notes/new.md");
        assertThat(captor.getValue().getContentType()).isEqualTo(DocumentEntity.ContentType.TEXT);
    }
}
