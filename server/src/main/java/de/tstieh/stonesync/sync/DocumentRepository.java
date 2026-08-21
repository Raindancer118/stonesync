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
     * word - exactly what's typed so far in a live/tab-complete search box - still finds things:
     * {@code similarity()} against the note title ({@code current_path}, short enough that
     * whole-string similarity is meaningful) and {@code word_similarity()} against the note/
     * attachment plaintext ({@code word_similarity} rather than plain {@code similarity}, because
     * the query is short and {@code plain_text} can be a whole document - plain {@code similarity}
     * would dilute a real match to near-zero by comparing against the *entire* text's trigram set
     * instead of just the best-matching substring within it).
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
                    OR similarity(d.current_path, :query) > 0.3
                    OR word_similarity(:query, coalesce(d.plain_text, '')) > 0.3
                  )
            ORDER BY
              GREATEST(
                ts_rank(d.search_vector, websearch_to_tsquery('english', :query)),
                similarity(d.current_path, :query),
                word_similarity(:query, coalesce(d.plain_text, ''))
              ) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchRaw(@Param("vaultId") UUID vaultId, @Param("query") String query,
                              @Param("startSel") String startSel, @Param("endSel") String endSel,
                              @Param("limit") int limit);
}
