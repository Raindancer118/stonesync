package de.tstieh.stonesync.sync;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findByVaultId(UUID vaultId);

    Optional<DocumentEntity> findByVaultIdAndCurrentPath(UUID vaultId, String currentPath);

    /**
     * Ranked, typo-tolerant search within one vault: exact/stemmed full-text search over content
     * (migration V7's {@code search_vector}/GIN index, {@code websearch_to_tsquery} - used
     * deliberately over {@code to_tsquery}, since it never throws on arbitrary free-text input
     * like quotes or stray punctuation) OR'd together with pg_trgm fuzzy matching (migration V8)
     * against the note TITLE only ({@code current_path}) - so a garbled/mistyped note name (the
     * concrete case this was built for: typing "Moatseting" finds "Monatsmeeting.md") still finds
     * the note, without needing the exact stemmed words full-text search requires.
     *
     * <p>{@code word_similarity()}, not plain {@code similarity()}: it finds the best-aligned
     * substring within the target instead of scoring the whole target string, so a
     * folder-qualified path like {@code "Notes/Monatsmeeting.md"} scores well (~0.45) against a
     * garbled query even though the folder and extension are noise it has to see past.</p>
     *
     * <p>Deliberately NOT extended to fuzzy-match {@code plain_text}: tried and reverted after a
     * live search against the real ~700-document production vault showed it doesn't work at that
     * scale. Trigram similarity has a discrete, coarsely-quantized range for a query this short (a
     * handful of achievable ratios, not a smooth 0-1 scale), so completely unrelated documents'
     * incidental local alignments regularly land on the exact same value as a genuine match's
     * score - no threshold could be found that kept the real "Monatsmeeting" match while excluding
     * ~20 unrelated documents (ordinary Obsidian help pages) that happened to tie it. Exact/
     * stemmed full-text search already covers typo-free content search with no such noise problem;
     * fuzzy matching is scoped to the title, where it's short and precise, and stays that way.</p>
     *
     * <p>Returns raw {@code Object[]} rows (id, current_path, content_type, snippet) rather than
     * an interface projection - deliberately, since the reliability of Spring Data's
     * column-name-to-property matching for native queries with computed/aliased columns is
     * fragile; a small amount of manual mapping ({@code DocumentSearchService}) is more
     * predictable. {@code startSel}/{@code endSel} are rare control characters, not literal HTML,
     * so the caller can safely HTML-escape the whole snippet before turning them into
     * {@code <mark>} tags - see {@code DocumentSearchService}.</p>
     */
    @Query(value = """
            SELECT d.id, d.current_path, d.content_type,
                   ts_headline('english', coalesce(d.plain_text, ''), websearch_to_tsquery('english', :query),
                       'MaxFragments=1,MaxWords=25,MinWords=8,StartSel=' || :startSel || ',StopSel=' || :endSel)
                       AS snippet
            FROM documents d
            WHERE d.vault_id = :vaultId
              AND d.deleted_at IS NULL
              AND (
                    d.search_vector @@ websearch_to_tsquery('english', :query)
                    OR word_similarity(:query, d.current_path) > 0.3
                  )
            ORDER BY
              GREATEST(
                ts_rank(d.search_vector, websearch_to_tsquery('english', :query)),
                word_similarity(:query, d.current_path)
              ) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchRaw(@Param("vaultId") UUID vaultId, @Param("query") String query,
                              @Param("startSel") String startSel, @Param("endSel") String endSel,
                              @Param("limit") int limit);
}
