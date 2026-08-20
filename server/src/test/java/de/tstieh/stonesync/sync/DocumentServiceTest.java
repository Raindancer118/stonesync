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
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository repository;

    @Mock
    private VaultAccessService vaultAccessService;

    @Mock
    private DocumentDeletionBroadcaster deletionBroadcaster;

    @Mock
    private DocumentGitEraser gitEraser;

    @Mock
    private VaultEventBroadcaster vaultEventBroadcaster;

    @Mock
    private de.tstieh.stonesync.audit.AuditService auditService;

    @Mock
    private CrossVaultLinkMaintainer linkMaintainer;

    private DocumentService service;
    private final UUID documentId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new DocumentService(repository, vaultAccessService, deletionBroadcaster, gitEraser, vaultEventBroadcaster, auditService, linkMaintainer, clock);
        // Default for the tests that are not about permissions: everything is readable.
        lenient().when(vaultAccessService.canRead(any(), any(), anyString())).thenReturn(true);
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
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService)
                .requirePathPermission(eq(userId), eq(vaultId), anyString(), any());

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
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService)
                .requirePathPermission(eq(userId), eq(vaultId), anyString(), any());

        assertThatThrownBy(() -> service.markDeleted(userId, documentId))
                .isInstanceOf(VaultAccessDeniedException.class);
        assertThat(doc.isDeleted()).isFalse();
        verify(deletionBroadcaster, never()).broadcastDeleteNotice(any());
    }

    @Test
    @DisplayName("deleting broadcasts a DELETE_NOTICE to any currently-connected sessions for that document")
    void deleteBroadcastsDeleteNotice() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));

        service.markDeleted(userId, documentId);

        verify(deletionBroadcaster).broadcastDeleteNotice(documentId);
    }

    @Test
    @DisplayName("a real user-initiated delete also erases the document from git history, so a later restore can't resurrect it")
    void deleteErasesFromGitHistory() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));

        service.markDeleted(userId, documentId);

        verify(gitEraser).removeFromGit(vaultId, "path.md");
    }

    @Test
    @DisplayName("markDeletedForRestore does NOT erase from git history - it mirrors an older state, not a new deletion")
    void markDeletedForRestoreDoesNotEraseFromGit() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));

        service.markDeletedForRestore(documentId);

        verify(gitEraser, never()).removeFromGit(any(), any());
    }

    @Test
    @DisplayName("resolving an unknown (vaultId, path) broadcasts a vault-wide document_created event")
    void resolveCreatingNewDocumentBroadcastsCreatedEvent() {
        when(repository.findByVaultIdAndCurrentPath(vaultId, "notes/new.md")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UUID resolved = service.resolveOrCreate(userId, vaultId, "notes/new.md", DocumentEntity.ContentType.TEXT, "session-abc");

        verify(vaultEventBroadcaster).notifyDocumentCreated(vaultId, resolved, "notes/new.md", DocumentEntity.ContentType.TEXT, "session-abc");
    }

    @Test
    @DisplayName("resolving a known (vaultId, path) does NOT broadcast a document_created event")
    void resolveExistingDocumentDoesNotBroadcastCreatedEvent() {
        DocumentEntity existing = new DocumentEntity(documentId, vaultId, "notes/a.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findByVaultIdAndCurrentPath(vaultId, "notes/a.md")).thenReturn(Optional.of(existing));

        service.resolveOrCreate(userId, vaultId, "notes/a.md", DocumentEntity.ContentType.TEXT);

        verify(vaultEventBroadcaster, never()).notifyDocumentCreated(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("deleting broadcasts a vault-wide document_deleted event")
    void deleteBroadcastsDocumentDeletedEvent() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));

        service.markDeleted(userId, documentId, "session-abc");

        verify(vaultEventBroadcaster).notifyDocumentDeleted(vaultId, documentId, "path.md", "session-abc");
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
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService)
                .requirePathPermission(eq(userId), eq(vaultId), anyString(), any());

        assertThatThrownBy(() -> service.resolveOrCreate(userId, vaultId, "notes/new.md", DocumentEntity.ContentType.TEXT))
                .isInstanceOf(VaultAccessDeniedException.class);

        // The lookup itself is harmless (nothing is returned to the caller); what must never
        // happen is a document being created for someone who may not write there.
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a note the caller may not read is not even listed - it never reaches their device")
    void listDocumentsHidesNotesWithoutReadAccess() {
        DocumentEntity visible = new DocumentEntity(UUID.randomUUID(), vaultId, "Shared/plan.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2026-01-01T00:00:00Z"));
        DocumentEntity secret = new DocumentEntity(UUID.randomUUID(), vaultId, "Privat/diary.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findByVaultId(vaultId)).thenReturn(List.of(visible, secret));
        when(vaultAccessService.canRead(userId, vaultId, "Shared/plan.md")).thenReturn(true);
        when(vaultAccessService.canRead(userId, vaultId, "Privat/diary.md")).thenReturn(false);

        List<DocumentService.DocumentSummary> listed = service.listDocuments(userId, vaultId);

        assertThat(listed).extracting(DocumentService.DocumentSummary::path).containsExactly("Shared/plan.md");
    }

    @Test
    @DisplayName("listing documents without vault access fails before the repository is ever queried (IDOR protection)")
    void listDocumentsWithoutVaultAccessIsDenied() {
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService)
                .requireVaultPermission(eq(userId), eq(vaultId), any());

        assertThatThrownBy(() -> service.listDocuments(userId, vaultId))
                .isInstanceOf(VaultAccessDeniedException.class);

        verify(repository, never()).findByVaultId(any());
    }

    @Test
    @DisplayName("listing documents excludes tombstoned (deleted) documents")
    void listDocumentsExcludesDeletedDocuments() {
        DocumentEntity alive = new DocumentEntity(documentId, vaultId, "notes/a.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        DocumentEntity deleted = new DocumentEntity(UUID.randomUUID(), vaultId, "notes/b.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        deleted.markDeleted(Instant.parse("2025-12-15T00:00:00Z"));
        when(repository.findByVaultId(vaultId)).thenReturn(List.of(alive, deleted));

        List<DocumentService.DocumentSummary> result = service.listDocuments(userId, vaultId);

        assertThat(result).extracting(DocumentService.DocumentSummary::id).containsExactly(documentId);
    }

    @Test
    @DisplayName("listing documents returns the correct id, path and content type for each non-deleted document")
    void listDocumentsReturnsCorrectData() {
        DocumentEntity textDoc = new DocumentEntity(documentId, vaultId, "notes/a.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        UUID attachmentId = UUID.randomUUID();
        DocumentEntity attachmentDoc = new DocumentEntity(attachmentId, vaultId, "images/pic.png",
                DocumentEntity.ContentType.ATTACHMENT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findByVaultId(vaultId)).thenReturn(List.of(textDoc, attachmentDoc));

        List<DocumentService.DocumentSummary> result = service.listDocuments(userId, vaultId);

        assertThat(result).containsExactlyInAnyOrder(
                new DocumentService.DocumentSummary(documentId, "notes/a.md", DocumentEntity.ContentType.TEXT),
                new DocumentService.DocumentSummary(attachmentId, "images/pic.png", DocumentEntity.ContentType.ATTACHMENT));
    }

    @Test
    @DisplayName("locate returns the document's vault and current path")
    void locateReturnsVaultAndPath() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "notes/a.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));

        DocumentService.DocumentLocation location = service.locate(userId, documentId);

        assertThat(location.vaultId()).isEqualTo(vaultId);
        assertThat(location.path()).isEqualTo("notes/a.md");
    }

    @Test
    @DisplayName("locate without vault access fails (IDOR protection)")
    void locateWithoutVaultAccessIsDenied() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "notes/a.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService)
                .requirePathPermission(eq(userId), eq(vaultId), anyString(), any());

        assertThatThrownBy(() -> service.locate(userId, documentId)).isInstanceOf(VaultAccessDeniedException.class);
    }

    @Test
    @DisplayName("listNonDeletedForRestore excludes tombstoned documents, with no user access check")
    void listNonDeletedForRestoreExcludesDeleted() {
        DocumentEntity alive = new DocumentEntity(documentId, vaultId, "notes/a.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        DocumentEntity deleted = new DocumentEntity(UUID.randomUUID(), vaultId, "notes/b.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        deleted.markDeleted(Instant.parse("2025-12-15T00:00:00Z"));
        when(repository.findByVaultId(vaultId)).thenReturn(List.of(alive, deleted));

        List<DocumentService.DocumentSummary> result = service.listNonDeletedForRestore(vaultId);

        assertThat(result).extracting(DocumentService.DocumentSummary::id).containsExactly(documentId);
        verify(vaultAccessService, never()).requirePathPermission(any(), any(), anyString(), any());
        verify(vaultAccessService, never()).requireVaultPermission(any(), any(), any());
    }

    @Test
    @DisplayName("resolveOrCreateForRestore creates a new document with no user access check")
    void resolveOrCreateForRestoreCreatesDocument() {
        when(repository.findByVaultIdAndCurrentPath(vaultId, "notes/new.md")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UUID resolved = service.resolveOrCreateForRestore(vaultId, "notes/new.md", DocumentEntity.ContentType.TEXT);

        assertThat(resolved).isNotNull();
        verify(vaultAccessService, never()).requirePathPermission(any(), any(), anyString(), any());
        verify(vaultAccessService, never()).requireVaultPermission(any(), any(), any());
    }

    @Test
    @DisplayName("markDeletedForRestore tombstones the document and broadcasts a DELETE_NOTICE, with no user access check")
    void markDeletedForRestoreTombstonesAndBroadcasts() {
        DocumentEntity doc = new DocumentEntity(documentId, vaultId, "path.md",
                DocumentEntity.ContentType.TEXT, Instant.parse("2025-12-01T00:00:00Z"));
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));

        service.markDeletedForRestore(documentId);

        assertThat(doc.isDeleted()).isTrue();
        verify(deletionBroadcaster).broadcastDeleteNotice(documentId);
        verify(vaultAccessService, never()).requirePathPermission(any(), any(), anyString(), any());
        verify(vaultAccessService, never()).requireVaultPermission(any(), any(), any());
    }
}
