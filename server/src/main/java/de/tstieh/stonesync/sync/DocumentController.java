package de.tstieh.stonesync.sync;

import de.tstieh.stonesync.search.DocumentSearchService;
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
import org.springframework.web.bind.annotation.RequestHeader;
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

    /**
     * Opaque, client-chosen id (one per plugin instance/device) that lets the vault-events
     * channel tell a client apart from its own actions - see {@link VaultEventBroadcaster}.
     * Optional: absent for callers that don't care about self-echo filtering.
     */
    public static final String SESSION_HEADER = "X-StoneSync-Session";

    private static final int MAX_SEARCH_RESULTS = 20;

    private final DocumentService documentService;
    private final DocumentSearchService searchService;

    public DocumentController(DocumentService documentService, DocumentSearchService searchService) {
        this.documentService = documentService;
        this.searchService = searchService;
    }

    @GetMapping
    public List<DocumentService.DocumentSummary> list(@RequestParam UUID vaultId, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return documentService.listDocuments(userId, vaultId);
    }

    /**
     * JSON counterpart to the HTML search results at {@code /dashboard/vaults/{id}/search} - same
     * {@link DocumentSearchService} (same GIN-indexed Postgres full-text search, same per-path
     * access filtering), but Bearer-API-key-authenticated like every other {@code /api/**}
     * endpoint, for the plugin's own in-Obsidian search (quick-search modal and the home view) to
     * call directly instead of screen-scraping the dashboard's HTML.
     */
    @GetMapping("/search")
    public List<SearchHitResponse> search(@RequestParam UUID vaultId, @RequestParam(defaultValue = "") String q,
                                           Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return searchService.search(userId, vaultId, q, MAX_SEARCH_RESULTS).stream()
                .map(hit -> new SearchHitResponse(hit.id(), hit.path(), hit.contentType(), hit.snippetHtml()))
                .toList();
    }

    @PostMapping("/resolve")
    public ResolveResponse resolve(@RequestBody ResolveRequest request,
                                    @RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                                    Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        UUID documentId = documentService.resolveOrCreate(userId, request.vaultId(), request.path(), request.contentType(), sessionId);
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
    public ResponseEntity<Void> delete(@PathVariable UUID documentId,
                                        @RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                                        Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        documentService.markDeleted(userId, documentId, sessionId);
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

    public record SearchHitResponse(UUID id, String path, DocumentEntity.ContentType contentType, String snippetHtml) {
    }
}
