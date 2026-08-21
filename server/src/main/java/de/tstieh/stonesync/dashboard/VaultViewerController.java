package de.tstieh.stonesync.dashboard;

import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.admin.VaultEntity;
import de.tstieh.stonesync.admin.VaultRepository;
import de.tstieh.stonesync.attachments.AttachmentService;
import de.tstieh.stonesync.invite.HtmlEscaper;
import de.tstieh.stonesync.search.DocumentSearchService;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentNotFoundException;
import de.tstieh.stonesync.sync.DocumentRepository;
import de.tstieh.stonesync.sync.DocumentService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only vault viewer: browse a vault's notes/attachments, read a note as rendered HTML, view
 * or download an attachment, and search - all gated by the same per-path access rules as every
 * other surface ({@code VaultAccessService}). Deliberately read-only: editing content stays
 * Obsidian's/the plugin's job (see {@code Project.md} - "the server is a dumb blob relay"); this
 * controller never writes to a document.
 */
@Controller
@RequestMapping("/dashboard/vaults/{vaultId}")
public class VaultViewerController {

    private static final Map<String, String> INLINE_CONTENT_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"));

    private final UserRepository userRepository;
    private final VaultRepository vaultRepository;
    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final AttachmentService attachmentService;
    private final DocumentSearchService searchService;

    public VaultViewerController(UserRepository userRepository, VaultRepository vaultRepository,
                                  DocumentRepository documentRepository, DocumentService documentService,
                                  AttachmentService attachmentService, DocumentSearchService searchService) {
        this.userRepository = userRepository;
        this.vaultRepository = vaultRepository;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.attachmentService = attachmentService;
        this.searchService = searchService;
    }

    @GetMapping
    public void browse(@AuthenticationPrincipal OidcUser oidcUser, @PathVariable UUID vaultId,
                        HttpServletResponse response) throws IOException {
        Optional<UserEntity> user = resolveUser(oidcUser, response);
        if (user.isEmpty()) {
            return;
        }
        String vaultName = vaultRepository.findById(vaultId).map(VaultEntity::getName).orElse("Vault");
        List<DocumentService.DocumentSummary> documents = documentService.listDocuments(user.get().getId(), vaultId);

        StringBuilder rows = new StringBuilder();
        documents.stream()
                .sorted((a, b) -> a.path().compareToIgnoreCase(b.path()))
                .forEach(doc -> rows.append(renderBrowseRow(vaultId, doc)));
        if (rows.isEmpty()) {
            rows.append("<p style=\"color:var(--text-muted);\">This vault is empty.</p>");
        }

        writePage(response, vaultName + " - StoneSync", vaultId, vaultName, """
                %s
                <section>
                  <div class="label">%s</div>
                  <div class="panel" style="padding:.5rem 1rem;">%s</div>
                </section>
                """.formatted(renderHeroSearch(vaultId, ""), HtmlEscaper.escape(vaultName), rows));
    }

    @GetMapping("/search")
    public void search(@AuthenticationPrincipal OidcUser oidcUser, @PathVariable UUID vaultId,
                        @RequestParam(defaultValue = "") String q, HttpServletResponse response) throws IOException {
        Optional<UserEntity> user = resolveUser(oidcUser, response);
        if (user.isEmpty()) {
            return;
        }
        String vaultName = vaultRepository.findById(vaultId).map(VaultEntity::getName).orElse("Vault");
        List<DocumentSearchService.SearchHit> hits = searchService.search(user.get().getId(), vaultId, q, 20);

        StringBuilder results = new StringBuilder();
        hits.forEach(hit -> results.append(renderSearchHit(vaultId, hit)));
        if (q.isBlank()) {
            results.append("<p style=\"color:var(--text-muted);\">Type something to search.</p>");
        } else if (hits.isEmpty()) {
            results.append("<p style=\"color:var(--text-muted);\">No matches for \"").append(HtmlEscaper.escape(q)).append("\".</p>");
        }

        writePage(response, "Search " + vaultName + " - StoneSync", vaultId, vaultName, """
                <section>
                  <div class="label">Search %s</div>
                  %s
                  %s
                </section>
                """.formatted(HtmlEscaper.escape(vaultName), renderCompactSearch(vaultId, q), results));
    }

    @GetMapping("/notes/{documentId}")
    public void note(@AuthenticationPrincipal OidcUser oidcUser, @PathVariable UUID vaultId,
                      @PathVariable UUID documentId, HttpServletResponse response) throws IOException {
        Optional<UserEntity> user = resolveUser(oidcUser, response);
        if (user.isEmpty()) {
            return;
        }
        documentService.locate(user.get().getId(), documentId); // enforces read access; throws otherwise
        DocumentEntity document = documentRepository.findById(documentId)
                .filter(doc -> doc.getVaultId().equals(vaultId))
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        String vaultName = vaultRepository.findById(vaultId).map(VaultEntity::getName).orElse("Vault");
        writePage(response, document.getCurrentPath() + " - StoneSync", vaultId, vaultName, """
                <section>
                  <div class="label">%s</div>
                  <article class="note">%s</article>
                </section>
                """.formatted(HtmlEscaper.escape(document.getCurrentPath()),
                MarkdownRenderer.render(document.getPlainText())));
    }

    @GetMapping("/attachments/{documentId}")
    public void attachment(@AuthenticationPrincipal OidcUser oidcUser, @PathVariable UUID vaultId,
                            @PathVariable UUID documentId, HttpServletResponse response) throws IOException {
        Optional<UserEntity> user = resolveUser(oidcUser, response);
        if (user.isEmpty()) {
            return;
        }
        DocumentService.DocumentLocation location = documentService.locate(user.get().getId(), documentId);
        if (!location.vaultId().equals(vaultId)) {
            throw new DocumentNotFoundException(documentId);
        }
        byte[] bytes = attachmentService.download(user.get().getId(), documentId);

        String extension = extensionOf(location.path());
        String contentType = INLINE_CONTENT_TYPES.get(extension);
        String disposition = contentType != null ? "inline" : "attachment";
        String filename = location.path().substring(location.path().lastIndexOf('/') + 1);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType != null ? contentType : "application/octet-stream");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                disposition + "; filename=\"" + filename.replace("\"", "") + "\"");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private String renderBrowseRow(UUID vaultId, DocumentService.DocumentSummary doc) {
        String href = doc.contentType() == DocumentEntity.ContentType.TEXT
                ? "/dashboard/vaults/" + vaultId + "/notes/" + doc.id()
                : "/dashboard/vaults/" + vaultId + "/attachments/" + doc.id();
        String icon = doc.contentType() == DocumentEntity.ContentType.TEXT ? "◆" : "▣";
        return """
                <div class="file-row"><span class="glyph">%s</span><a href="%s">%s</a></div>
                """.formatted(icon, href, HtmlEscaper.escape(doc.path()));
    }

    private String renderSearchHit(UUID vaultId, DocumentSearchService.SearchHit hit) {
        String href = hit.contentType() == DocumentEntity.ContentType.TEXT
                ? "/dashboard/vaults/" + vaultId + "/notes/" + hit.id()
                : "/dashboard/vaults/" + vaultId + "/attachments/" + hit.id();
        return """
                <div class="hit">
                  <a class="path" href="%s">%s</a>
                  <p class="snippet">%s</p>
                </div>
                """.formatted(href, HtmlEscaper.escape(hit.path()), hit.snippetHtml());
    }

    /**
     * The big, centered, Google-homepage-style search bar every vault page opens with - search
     * is the actual point of this surface, so it gets the most prominent spot on screen rather
     * than a small form buried under a heading.
     */
    private String renderHeroSearch(UUID vaultId, String currentQuery) {
        return """
                <div class="hero-search">
                  <div class="wordmark"><em>Stone</em>Sync</div>
                  <form method="get" action="/dashboard/vaults/%s/search">
                    <input type="text" name="q" value="%s" placeholder="Search notes, PDFs, screenshots…" required/>
                    <button type="submit" class="icon-btn" aria-label="Search">&#8981;</button>
                  </form>
                  <div class="hint">Searches every note and attachment in this vault, OCR included.</div>
                </div>
                """.formatted(vaultId, HtmlEscaper.escape(currentQuery));
    }

    /** A small, non-hero re-search box atop the results themselves - once you've already
     * searched, the results are the point, not another full-screen search moment. */
    private String renderCompactSearch(UUID vaultId, String currentQuery) {
        return """
                <form method="get" action="/dashboard/vaults/%s/search" class="search-box">
                  <input type="text" name="q" value="%s" placeholder="Search this vault..." required/>
                  <button type="submit" class="btn">Search</button>
                </form>
                """.formatted(vaultId, HtmlEscaper.escape(currentQuery));
    }

    private Optional<UserEntity> resolveUser(OidcUser oidcUser, HttpServletResponse response) throws IOException {
        Optional<UserEntity> user = userRepository.findByEmail(oidcUser.getEmail());
        if (user.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(PageShell.centered("StoneSync - No account", """
                    <h1>No StoneSync account</h1>
                    <p class="lede">Please use an invite link first.</p>
                    """));
        }
        return user;
    }

    private void writePage(HttpServletResponse response, String title, UUID vaultId, String vaultName,
                            String bodyHtml) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html;charset=UTF-8");
        String crumb = """
                <div class="crumb">%s</div>
                <a href="/dashboard/vaults/%s">Browse</a>
                <a href="/dashboard/vaults/%s/search">Search</a>
                """.formatted(HtmlEscaper.escape(vaultName), vaultId, vaultId);
        response.getWriter().write(PageShell.page(title, PageShell.rail(crumb), bodyHtml));
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
