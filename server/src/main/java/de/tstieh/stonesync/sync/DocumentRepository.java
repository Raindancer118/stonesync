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
     * Ranked, typo-tolerant search within one vault: exact/stemmed full-text search (migration
     * V7's {@code search_vector}/GIN index, {@code websearch_to_tsquery} - used deliberately over
     * {@code to_tsquery}, since it never throws on arbitrary free-text input like quotes or stray
     * punctuation) OR'd together with pg_trgm fuzzy matching (migration V8) so a typo or a partial
     * word - exactly what's typed so far in a live/tab-complete search box - still finds things.
     *
     * <p>Both fuzzy comparisons use {@code word_similarity()}, not plain {@code similarity()}: it
     * finds the best-aligned substring within the target instead of scoring the whole target
     * string, which matters even for {@code current_path} - a folder-qualified path like
     * {@code "Notes/Monatsmeeting.md"} scores far better against a garbled {@code "Moatseting"}
     * this way (~0.45) than comparing the whole path including the folder and extension (~0.27).</p>
     *
     * <p>The two fuzzy comparisons use different thresholds for a reason found via a live search
     * against the real ~700-document production vault: {@code current_path} is short, so a 0.3
     * threshold stays precise. {@code plain_text} can be an entire document, and at that scale
     * {@code word_similarity} against a short, heavily-garbled query is noisy - dozens of unrelated
     * documents can coincidentally clear a low bar purely because there is so much text to search
     * an unlucky local alignment in, which crowded a genuine match out of the ranked result window
     * entirely. 0.45 cuts that noise down to a manageable level while still catching real typos in
     * body text; the sharper "wrong note title" case above is what the lower path threshold is for.</p>
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
                    OR word_similarity(:query, coalesce(d.plain_text, '')) > 0.45
                  )
            ORDER BY
              GREATEST(
                ts_rank(d.search_vector, websearch_to_tsquery('english', :query)),
                word_similarity(:query, d.current_path),
                word_similarity(:query, coalesce(d.plain_text, ''))
              ) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchRaw(@Param("vaultId") UUID vaultId, @Param("query") String query,
                              @Param("startSel") String startSel, @Param("endSel") String endSel,
                              @Param("limit") int limit);
}
