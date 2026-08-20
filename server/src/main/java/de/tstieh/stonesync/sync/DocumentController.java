package de.tstieh.stonesync.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Document metadata endpoints (rename, tombstone-delete) - deliberately separate from the
 * Yjs binary WebSocket channel, since neither operation touches document content. Every
 * endpoint enforces vault-level authorization in {@link DocumentService} using the
 * authenticated caller's userId.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public List<DocumentService.DocumentSummary> list(@RequestParam UUID vaultId, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return documentService.listDocuments(userId, vaultId);
    }

    @PostMapping("/resolve")
    public ResolveResponse resolve(@RequestBody ResolveRequest request, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        UUID documentId = documentService.resolveOrCreate(userId, request.vaultId(), request.path(), request.contentType());
        return new ResolveResponse(documentId);
    }

    @PatchMapping("/{documentId}/path")
    public ResponseEntity<Void> rename(@PathVariable UUID documentId, @RequestBody RenameRequest request,
                                        Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        documentService.rename(userId, documentId, request.newPath());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        documentService.markDeleted(userId, documentId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public void handleNotFound() {
        // body-less 404
    }

    public record RenameRequest(@NotBlank String newPath) {
    }

    public record ResolveRequest(@NotNull UUID vaultId, @NotBlank String path,
                                  @NotNull DocumentEntity.ContentType contentType) {
    }

    public record ResolveResponse(UUID documentId) {
    }
}
