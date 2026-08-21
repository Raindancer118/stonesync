package de.tstieh.stonesync.search;

import de.tstieh.stonesync.access.Permission;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentSearchServiceTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private VaultAccessService vaultAccessService;

    private DocumentSearchService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DocumentSearchService(documentRepository, vaultAccessService);
    }

    @Test
    void requiresAtLeastVaultReadAccessBeforeSearching() {
        when(documentRepository.searchRaw(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        service.search(userId, vaultId, "budget", 20);

        verify(vaultAccessService).requireVaultPermission(userId, vaultId, Permission.READ);
    }

    @Test
    void blankQueryReturnsNoResultsWithoutHittingTheDatabase() {
        List<DocumentSearchService.SearchHit> hits = service.search(userId, vaultId, "  ", 20);

        assertThat(hits).isEmpty();
        verify(documentRepository, org.mockito.Mockito.never()).searchRaw(any(), any(), any(), any(), anyInt());
    }

    @Test
    void hitsThePersonMayNotReadArePermanentlyFilteredOut() {
        UUID readableId = UUID.randomUUID();
        UUID hiddenId = UUID.randomUUID();
        when(documentRepository.searchRaw(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                row(readableId, "Notes/visible.md", "TEXT", "a <mark>match</mark>"),
                row(hiddenId, "Private/secret.md", "TEXT", "another match")
        ));
        when(vaultAccessService.canRead(userId, vaultId, "Notes/visible.md")).thenReturn(true);
        when(vaultAccessService.canRead(userId, vaultId, "Private/secret.md")).thenReturn(false);

        List<DocumentSearchService.SearchHit> hits = service.search(userId, vaultId, "match", 20);

        assertThat(hits).extracting(DocumentSearchService.SearchHit::id).containsExactly(readableId);
    }

    @Test
    void snippetHtmlEscapesUserContentAndOnlyThenRendersMarkTags() {
        UUID id = UUID.randomUUID();
        // The sentinel control characters ts_headline was asked to use for highlighting, around
        // some content that itself looks like an HTML/script injection attempt.
        String rawSnippet = "<script>alert(1)</script> " + "\u0001" + "found" + "\u0002" + " it";
        when(documentRepository.searchRaw(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.<Object[]>of(row(id, "Notes/x.md", "TEXT", rawSnippet)));
        when(vaultAccessService.canRead(any(), any(), any())).thenReturn(true);

        List<DocumentSearchService.SearchHit> hits = service.search(userId, vaultId, "found", 20);

        assertThat(hits.get(0).snippetHtml())
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt; <mark>found</mark> it");
    }

    private static Object[] row(UUID id, String path, String contentType, String snippet) {
        return new Object[]{id, path, contentType, snippet};
    }
}
