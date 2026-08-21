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
     * Ranked full-text search within one vault (see migration V7's {@code search_vector}/GIN
     * index). {@code websearch_to_tsquery} is used deliberately over {@code to_tsquery}: it never
     * throws on arbitrary free-text user input (quotes, "-", stray punctuation), which
     * {@code to_tsquery}'s boolean-operator syntax would.
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
              AND d.search_vector @@ websearch_to_tsquery('english', :query)
            ORDER BY ts_rank(d.search_vector, websearch_to_tsquery('english', :query)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchRaw(@Param("vaultId") UUID vaultId, @Param("query") String query,
                              @Param("startSel") String startSel, @Param("endSel") String endSel,
                              @Param("limit") int limit);
}
