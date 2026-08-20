package de.tstieh.stonesync.api;

import de.tstieh.stonesync.access.Permission;
import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.admin.VaultRepository;
import de.tstieh.stonesync.links.DocumentLinkEntity;
import de.tstieh.stonesync.links.LinkIndexService;
import de.tstieh.stonesync.links.LinkRewriteEntity;
import de.tstieh.stonesync.links.LinkRewriteService;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-vault links: resolving {@code [[slug:Note]]} to a real document, permission-filtered
 * backlinks, and the queue of link repairs a client should apply.
 *
 * <p>A link the caller may not follow comes back as {@code RESTRICTED}, never as "not found" and
 * never with the target's title: whether a note exists, and what it is called, can itself be
 * sensitive. The client shows a lock instead of a broken link, which is also the honest signal -
 * the link is fine, the permission is missing.</p>
 */
@RestController
@RequestMapping("/api/links")
public class LinkController {

    private final LinkIndexService linkIndexService;
    private final LinkRewriteService linkRewriteService;
    private final VaultAccessService vaultAccessService;
    private final DocumentRepository documentRepository;
    private final VaultRepository vaultRepository;

    public LinkController(LinkIndexService linkIndexService, LinkRewriteService linkRewriteService,
                           VaultAccessService vaultAccessService, DocumentRepository documentRepository,
                           VaultRepository vaultRepository) {
        this.linkIndexService = linkIndexService;
        this.linkRewriteService = linkRewriteService;
        this.vaultAccessService = vaultAccessService;
        this.documentRepository = documentRepository;
        this.vaultRepository = vaultRepository;
    }

    public enum LinkStatus {
        /** Resolved, and the caller may open it. */
        AVAILABLE,
        /** It exists, but not for this caller - deliberately indistinguishable from "exists". */
        RESTRICTED,
        /** No such vault namespace, or no such note in it. */
        NOT_FOUND
    }

    @GetMapping("/resolve")
    public ResolvedLink resolve(@RequestParam String vault, @RequestParam String path, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        Optional<DocumentEntity> target = linkIndexService.resolve(vault, path);
        if (target.isEmpty()) {
            return new ResolvedLink(LinkStatus.NOT_FOUND, null, null, null, false);
        }
        DocumentEntity document = target.get();
        if (!vaultAccessService.canRead(userId, document.getVaultId(), document.getCurrentPath())) {
            return new ResolvedLink(LinkStatus.RESTRICTED, null, null, null, false);
        }
        boolean writable = vaultAccessService.canWrite(userId, document.getVaultId(), document.getCurrentPath());
        return new ResolvedLink(LinkStatus.AVAILABLE, document.getId().toString(), document.getVaultId().toString(),
                document.getCurrentPath(), writable);
    }

    /** Notes linking here - only those the caller may actually see. */
    @GetMapping("/backlinks/{documentId}")
    public List<Backlink> backlinks(@PathVariable UUID documentId, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        DocumentEntity target = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document " + documentId));
        vaultAccessService.requirePathPermission(userId, target.getVaultId(), target.getCurrentPath(), Permission.READ);

        String slug = vaultRepository.findById(target.getVaultId()).map(vault -> vault.getSlug()).orElse(null);
        return linkIndexService.backlinksTo(documentId, slug, target.getCurrentPath()).stream()
                .map(link -> toBacklink(userId, link))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Link repairs waiting for this note (see {@code LinkRewriteService}). Requires write access -
     * a reader could not perform the edit anyway.
     */
    @GetMapping("/rewrites/{documentId}")
    public List<PendingRewrite> rewrites(@PathVariable UUID documentId, Authentication authentication) {
        DocumentEntity document = requireWritable(documentId, authentication);
        return linkRewriteService.pendingFor(document.getId()).stream()
                .map(rewrite -> new PendingRewrite(rewrite.getId(), rewrite.getOldLink(), rewrite.getNewLink()))
                .toList();
    }

    @PostMapping("/rewrites/{documentId}/{rewriteId}/applied")
    public ResponseEntity<Void> markApplied(@PathVariable UUID documentId, @PathVariable long rewriteId,
                                             Authentication authentication) {
        DocumentEntity document = requireWritable(documentId, authentication);
        linkRewriteService.markApplied(document.getId(), rewriteId);
        return ResponseEntity.noContent().build();
    }

    private DocumentEntity requireWritable(UUID documentId, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document " + documentId));
        vaultAccessService.requirePathPermission(userId, document.getVaultId(), document.getCurrentPath(),
                Permission.WRITE);
        return document;
    }

    /** A backlink is only reported if its *source* note is readable for the caller, too. */
    private Optional<Backlink> toBacklink(UUID userId, DocumentLinkEntity link) {
        return documentRepository.findById(link.getSourceDocumentId())
                .filter(source -> !source.isDeleted())
                .filter(source -> vaultAccessService.canRead(userId, source.getVaultId(), source.getCurrentPath()))
                .map(source -> new Backlink(source.getId().toString(), source.getVaultId().toString(),
                        source.getCurrentPath(),
                        vaultRepository.findById(source.getVaultId()).map(vault -> vault.getSlug()).orElse(null),
                        link.getLinkText()));
    }

    @ExceptionHandler(VaultAccessDeniedException.class)
    public ResponseEntity<Void> handleDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleUnknown() {
        return ResponseEntity.notFound().build();
    }

    public record ResolvedLink(LinkStatus status, String documentId, String vaultId, String path, boolean writable) {
    }

    public record Backlink(String documentId, String vaultId, String path, String vaultSlug, String linkText) {
    }

    public record PendingRewrite(long id, String oldLink, String newLink) {
    }
}
