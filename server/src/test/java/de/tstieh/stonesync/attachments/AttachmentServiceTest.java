package de.tstieh.stonesync.attachments;

import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.search.AttachmentTextExtractionService;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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
    @Mock
    private de.tstieh.stonesync.audit.AuditService auditService;
    @Mock
    private AttachmentTextExtractionService textExtractionService;

    private AttachmentService service;
    private final UUID documentId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AttachmentService(repository, storage, documentService, vaultAccessService, auditService,
                textExtractionService);
        lenient().when(documentService.vaultIdOf(documentId)).thenReturn(vaultId);
        lenient().when(documentService.locateForWrite(userId, documentId))
                .thenReturn(new DocumentService.DocumentLocation(vaultId, "assets/image.png"));
        lenient().when(documentService.locate(userId, documentId))
                .thenReturn(new DocumentService.DocumentLocation(vaultId, "assets/image.png"));
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
        doThrow(new VaultAccessDeniedException("denied")).when(documentService).locateForWrite(userId, documentId);

        assertThatThrownBy(() -> service.upload(userId, documentId, hash, bytes, Instant.now()))
                .isInstanceOf(VaultAccessDeniedException.class);

        verify(storage, never()).store(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("downloading without vault access fails before any bytes are ever read (IDOR protection)")
    void downloadWithoutVaultAccessIsDenied() {
        doThrow(new VaultAccessDeniedException("denied")).when(documentService).locate(userId, documentId);

        assertThatThrownBy(() -> service.download(userId, documentId))
                .isInstanceOf(VaultAccessDeniedException.class);

        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("downloading a document with no stored attachment fails with a clean, dedicated error")
    void downloadOfNonExistentAttachmentFailsCleanly() {
        when(repository.findById(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.download(userId, documentId))
                .isInstanceOf(AttachmentNotFoundException.class);
    }

    @Test
    @DisplayName("download reads back the exact bytes that were stored, round-tripped through the real filesystem storage")
    void downloadRoundTripsRealBytesThroughFilesystemStorage(@TempDir Path tempDir) {
        FileSystemAttachmentStorage realStorage = new FileSystemAttachmentStorage(new StorageProperties(tempDir.toString()));
        AttachmentService realService = new AttachmentService(repository, realStorage, documentService, vaultAccessService,
                auditService, textExtractionService);
        byte[] bytes = "round-trip content, exact bytes expected back".getBytes();
        String hash = sha256Hex(bytes);
        when(repository.findById(documentId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        realService.upload(userId, documentId, hash, bytes, Instant.now());

        ArgumentCaptor<AttachmentEntity> captor = ArgumentCaptor.forClass(AttachmentEntity.class);
        verify(repository).save(captor.capture());
        when(repository.findById(documentId)).thenReturn(Optional.of(captor.getValue()));

        byte[] downloaded = realService.download(userId, documentId);

        assertThat(downloaded).isEqualTo(bytes);
    }

    @Test
    @DisplayName("reindexing a vault queues extraction for every existing attachment, but skips notes and missing rows")
    void reindexVaultQueuesExtractionForExistingAttachmentsOnly() {
        UUID vaultId = UUID.randomUUID();
        UUID attachmentDocId = UUID.randomUUID();
        UUID noteDocId = UUID.randomUUID();
        UUID orphanedAttachmentDocId = UUID.randomUUID();
        when(documentService.listNonDeletedForRestore(vaultId)).thenReturn(List.of(
                new DocumentService.DocumentSummary(attachmentDocId, "Assets/photo.png", DocumentEntity.ContentType.ATTACHMENT),
                new DocumentService.DocumentSummary(noteDocId, "Notes/plan.md", DocumentEntity.ContentType.TEXT),
                new DocumentService.DocumentSummary(orphanedAttachmentDocId, "Assets/gone.png", DocumentEntity.ContentType.ATTACHMENT)
        ));
        byte[] bytes = {5, 6, 7};
        when(repository.findById(attachmentDocId)).thenReturn(
                Optional.of(new AttachmentEntity(attachmentDocId, "hash", bytes.length, "/data/vault/hash", Instant.now())));
        when(repository.findById(orphanedAttachmentDocId)).thenReturn(Optional.empty());
        when(storage.load("/data/vault/hash")).thenReturn(bytes);

        int queued = service.reindexVault(vaultId);

        assertThat(queued).isEqualTo(1);
        verify(textExtractionService).extractAndIndex(attachmentDocId, bytes, "Assets/photo.png");
        verify(textExtractionService, never()).extractAndIndex(eq(noteDocId), any(), any());
        verify(textExtractionService, never()).extractAndIndex(eq(orphanedAttachmentDocId), any(), any());
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
