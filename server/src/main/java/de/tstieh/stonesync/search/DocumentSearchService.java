package de.tstieh.stonesync.search;

import de.tstieh.stonesync.access.Permission;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.invite.HtmlEscaper;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Full-text search across a vault's notes and attachments (see migration V7), scoped by the same
 * per-path access rules as everything else - a search result is exactly as visible as the
 * document it points to, never more.
 */
@Service
public class DocumentSearchService {

    /**
     * Rare control characters, not literal HTML - {@link DocumentRepository#searchRaw} hands them
     * to Postgres' {@code ts_headline} as the highlight delimiters. The whole snippet is
     * HTML-escaped first and only THEN are these two characters turned into real {@code <mark>}
     * tags (see {@link #toHit}) - otherwise a note or PDF whose text happens to contain
     * {@code <script>} would render that literally in the search-results page.
     */
    private static final String START_SEL = "\u0001";
    private static final String END_SEL = "\u0002";

    /** Over-fetch before per-path filtering, since some ranked hits may not be readable by this user. */
    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final int MIN_CANDIDATES = 50;

    private final DocumentRepository documentRepository;
    private final VaultAccessService vaultAccessService;

    public DocumentSearchService(DocumentRepository documentRepository, VaultAccessService vaultAccessService) {
        this.documentRepository = documentRepository;
        this.vaultAccessService = vaultAccessService;
    }

    public List<SearchHit> search(UUID userId, UUID vaultId, String query, int maxResults) {
        vaultAccessService.requireVaultPermission(userId, vaultId, Permission.READ);
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int candidateLimit = Math.max(maxResults * CANDIDATE_MULTIPLIER, MIN_CANDIDATES);
        List<Object[]> rows = documentRepository.searchRaw(vaultId, query, START_SEL, END_SEL, candidateLimit);
        return rows.stream()
                .map(DocumentSearchService::toHit)
                .filter(hit -> vaultAccessService.canRead(userId, vaultId, hit.path()))
                .limit(maxResults)
                .toList();
    }

    private static SearchHit toHit(Object[] row) {
        UUID id = (UUID) row[0];
        String path = (String) row[1];
        DocumentEntity.ContentType contentType = DocumentEntity.ContentType.valueOf((String) row[2]);
        String rawSnippet = (String) row[3];
        String snippetHtml = rawSnippet == null || rawSnippet.isBlank()
                ? ""
                : HtmlEscaper.escape(rawSnippet).replace(START_SEL, "<mark>").replace(END_SEL, "</mark>");
        return new SearchHit(id, path, contentType, snippetHtml);
    }

    public record SearchHit(UUID id, String path, DocumentEntity.ContentType contentType, String snippetHtml) {
    }
}
