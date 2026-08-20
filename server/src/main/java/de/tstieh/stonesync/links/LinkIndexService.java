package de.tstieh.stonesync.links;

import de.tstieh.stonesync.admin.VaultEntity;
import de.tstieh.stonesync.admin.VaultRepository;
import de.tstieh.stonesync.logging.AppLog;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The server-side link index: which note links into which other vault.
 *
 * <p>Fed from the materialize side-channel, the one place the server legitimately sees plaintext -
 * the Yjs sync path stays an opaque relay. Only cross-vault links are indexed; ordinary
 * {@code [[Note]]} links are Obsidian's own concern and must keep working with no server
 * involved, so the server neither stores nor touches them.</p>
 */
@Service
public class LinkIndexService {

    private final DocumentLinkRepository linkRepository;
    private final DocumentRepository documentRepository;
    private final VaultRepository vaultRepository;
    private final Clock clock;

    public LinkIndexService(DocumentLinkRepository linkRepository, DocumentRepository documentRepository,
                             VaultRepository vaultRepository, Clock clock) {
        this.linkRepository = linkRepository;
        this.documentRepository = documentRepository;
        this.vaultRepository = vaultRepository;
        this.clock = clock;
    }

    /** Replaces this document's indexed links with whatever its current content contains. */
    @Transactional
    public void reindex(UUID documentId, UUID vaultId, String content) {
        List<WikiLinks.CrossVaultLink> links = WikiLinks.crossVaultLinks(content);
        linkRepository.deleteBySourceDocumentId(documentId);
        if (links.isEmpty()) {
            return;
        }
        for (WikiLinks.CrossVaultLink link : links) {
            linkRepository.save(new DocumentLinkEntity(documentId, vaultId, link.vaultSlug(), link.targetPath(),
                    resolveTarget(link).map(DocumentEntity::getId).orElse(null), link.linkText(), clock.instant()));
        }
        AppLog.debug("Indexed {} cross-vault link(s) in document {}", links.size(), documentId);
    }

    /** Where a {@code [[slug:path]]} link actually points, if that vault and note exist. */
    public Optional<DocumentEntity> resolve(String vaultSlug, String targetPath) {
        return vaultRepository.findBySlug(vaultSlug)
                .flatMap(vault -> findNote(vault, WikiLinks.normalizeTarget(targetPath)));
    }

    /** Every note that links to the given one, from any vault. */
    public List<DocumentLinkEntity> backlinksTo(UUID documentId, String vaultSlug, String path) {
        List<DocumentLinkEntity> byId = linkRepository.findByTargetDocumentId(documentId);
        if (vaultSlug == null) {
            return byId;
        }
        List<DocumentLinkEntity> byPath =
                linkRepository.findByTargetVaultSlugAndTargetPath(vaultSlug, WikiLinks.normalizeTarget(path));
        // A link written before its target existed has no resolved id yet, so both lookups matter.
        return java.util.stream.Stream.concat(byId.stream(), byPath.stream())
                .collect(java.util.stream.Collectors.toMap(DocumentLinkEntity::getId, link -> link, (a, b) -> a))
                .values().stream().toList();
    }

    public List<DocumentLinkEntity> outboundFrom(UUID documentId) {
        return linkRepository.findBySourceDocumentId(documentId);
    }

    private Optional<DocumentEntity> resolveTarget(WikiLinks.CrossVaultLink link) {
        return vaultRepository.findBySlug(link.vaultSlug()).flatMap(vault -> findNote(vault, link.targetPath()));
    }

    /**
     * Links are written without the {@code .md} extension, so both spellings are tried - and a
     * tombstoned note never resolves, otherwise a deleted target would look reachable.
     */
    private Optional<DocumentEntity> findNote(VaultEntity vault, String normalizedPath) {
        return documentRepository.findByVaultIdAndCurrentPath(vault.getId(), normalizedPath + ".md")
                .or(() -> documentRepository.findByVaultIdAndCurrentPath(vault.getId(), normalizedPath))
                .filter(document -> !document.isDeleted());
    }
}
