package de.tstieh.stonesync.attachments;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock
    private AttachmentRepository repository;
    @Mock
    private FileSystemAttachmentStorage storage;

    private AttachmentService service;
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AttachmentService(repository, storage);
    }

    @Test
    @DisplayName("ein bekannter Hash wird als bekannt gemeldet")
    void statusReportsKnownHash() {
        when(repository.existsByContentHash("abc")).thenReturn(true);

        assertThat(service.isKnown("abc")).isTrue();
    }

    @Test
    @DisplayName("ein unbekannter Hash wird als unbekannt gemeldet")
    void statusReportsUnknownHash() {
        when(repository.existsByContentHash("xyz")).thenReturn(false);

        assertThat(service.isKnown("xyz")).isFalse();
    }

    @Test
    @DisplayName("Upload eines neuen Dokuments legt einen neuen Attachment-Eintrag an")
    void uploadCreatesNewEntryForUnknownDocument() {
        when(repository.findById(documentId)).thenReturn(Optional.empty());
        when(storage.store(eq("hash1"), any())).thenReturn("/data/vault/hash1");
        Instant modifiedAt = Instant.parse("2026-01-01T00:00:00Z");

        service.upload(documentId, "hash1", new byte[]{1, 2}, modifiedAt);

        verify(repository).save(any(AttachmentEntity.class));
    }

    @Test
    @DisplayName("Last-Writer-Wins: ein juengerer Upload ueberschreibt den bestehenden Eintrag")
    void newerUploadOverwritesExistingEntry() {
        AttachmentEntity existing = new AttachmentEntity(documentId, "old-hash", 1L, "/data/vault/old-hash",
                Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(existing));
        when(storage.store(eq("new-hash"), any())).thenReturn("/data/vault/new-hash");
        Instant newer = Instant.parse("2026-01-02T00:00:00Z");

        service.upload(documentId, "new-hash", new byte[]{9}, newer);

        assertThat(existing.getContentHash()).isEqualTo("new-hash");
        assertThat(existing.getModifiedAt()).isEqualTo(newer);
    }

    @Test
    @DisplayName("Last-Writer-Wins: ein aelterer, konkurrierender Upload wird verworfen")
    void olderConflictingUploadIsDiscarded() {
        AttachmentEntity existing = new AttachmentEntity(documentId, "current-hash", 1L, "/data/vault/current-hash",
                Instant.parse("2026-01-02T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(existing));

        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        service.upload(documentId, "stale-hash", new byte[]{9}, older);

        assertThat(existing.getContentHash()).isEqualTo("current-hash");
        verify(storage, never()).store(eq("stale-hash"), any());
    }
}
