package de.tstieh.stonesync.attachments;

import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.sync.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock
    private AttachmentRepository repository;
    @Mock
    private FileSystemAttachmentStorage storage;
    @Mock
    private DocumentService documentService;
    @Mock
    private VaultAccessService vaultAccessService;

    private AttachmentService service;
    private final UUID documentId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AttachmentService(repository, storage, documentService, vaultAccessService);
        lenient().when(documentService.vaultIdOf(documentId)).thenReturn(vaultId);
    }

    @Test
    @DisplayName("a known hash is reported as known")
    void statusReportsKnownHash() {
        when(repository.existsByContentHash("abc")).thenReturn(true);

        assertThat(service.isKnown("abc")).isTrue();
    }

    @Test
    @DisplayName("an unknown hash is reported as unknown")
    void statusReportsUnknownHash() {
        when(repository.existsByContentHash("xyz")).thenReturn(false);

        assertThat(service.isKnown("xyz")).isFalse();
    }

    @Test
    @DisplayName("uploading a new document creates a new attachment entry")
    void uploadCreatesNewEntryForUnknownDocument() {
        byte[] bytes = {1, 2};
        String hash = sha256Hex(bytes);
        when(repository.findById(documentId)).thenReturn(Optional.empty());
        when(storage.store(eq(hash), any())).thenReturn("/data/vault/" + hash);
        Instant modifiedAt = Instant.parse("2026-01-01T00:00:00Z");

        service.upload(userId, documentId, hash, bytes, modifiedAt);

        verify(repository).save(any(AttachmentEntity.class));
    }

    @Test
    @DisplayName("last-writer-wins: a newer upload overwrites the existing entry")
    void newerUploadOverwritesExistingEntry() {
        AttachmentEntity existing = new AttachmentEntity(documentId, "old-hash", 1L, "/data/vault/old-hash",
                Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(existing));
        byte[] bytes = {9};
        String hash = sha256Hex(bytes);
        when(storage.store(eq(hash), any())).thenReturn("/data/vault/" + hash);
        Instant newer = Instant.parse("2026-01-02T00:00:00Z");

        service.upload(userId, documentId, hash, bytes, newer);

        assertThat(existing.getContentHash()).isEqualTo(hash);
        assertThat(existing.getModifiedAt()).isEqualTo(newer);
    }

    @Test
    @DisplayName("last-writer-wins: an older, conflicting upload is discarded")
    void olderConflictingUploadIsDiscarded() {
        AttachmentEntity existing = new AttachmentEntity(documentId, "current-hash", 1L, "/data/vault/current-hash",
                Instant.parse("2026-01-02T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(existing));

        byte[] bytes = {9};
        String hash = sha256Hex(bytes);
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        service.upload(userId, documentId, hash, bytes, older);

        assertThat(existing.getContentHash()).isEqualTo("current-hash");
        verify(storage, never()).store(eq(hash), any());
    }

    @Test
    @DisplayName("a hash that does not match the actual SHA-256 of the bytes is rejected")
    void rejectsHashThatDoesNotMatchActualContent() {
        byte[] bytes = "some content".getBytes();

        assertThatThrownBy(() -> service.upload(userId, documentId, "not-the-real-hash", bytes, Instant.now()))
                .isInstanceOf(InvalidAttachmentHashException.class);

        verify(storage, never()).store(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a path traversal payload used as a hash is rejected before it ever reaches the storage layer")
    void rejectsPathTraversalPayloadAsHash() {
        byte[] bytes = "irrelevant".getBytes();

        assertThatThrownBy(() -> service.upload(userId, documentId, "../../../etc/cron.d/pwned", bytes, Instant.now()))
                .isInstanceOf(InvalidAttachmentHashException.class);

        verify(storage, never()).store(any(), any());
    }

    @Test
    @DisplayName("upload without vault access fails before any bytes are ever written (IDOR protection)")
    void uploadWithoutVaultAccessIsDenied() {
        byte[] bytes = {1, 2, 3};
        String hash = sha256Hex(bytes);
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService).requireAccess(userId, vaultId);

        assertThatThrownBy(() -> service.upload(userId, documentId, hash, bytes, Instant.now()))
                .isInstanceOf(VaultAccessDeniedException.class);

        verify(storage, never()).store(any(), any());
        verify(repository, never()).save(any());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
