package de.tstieh.stonesync.links;

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
 * Keeps cross-vault links pointing at the right note when that note is renamed.
 *
 * <p>The server never edits content itself - it has no idea what a Yjs update looks like. Instead
 * it works out <em>which</em> note needs <em>which</em> link replaced and queues that as an
 * instruction; a client applies it as an ordinary edit, which then reaches everyone through the
 * normal sync path. Clients that are connected get told immediately, everyone else picks the
 * instruction up the next time they open the note.</p>
 *
 * <p>Local {@code [[Note]]} links are never queued: Obsidian rewrites those itself on rename, and
 * a vault has to keep working with no server in reach.</p>
 */
@Service
public class LinkRewriteService implements de.tstieh.stonesync.sync.CrossVaultLinkMaintainer {

    private final DocumentLinkRepository linkRepository;
    private final LinkRewriteRepository rewriteRepository;
    private final DocumentRepository documentRepository;
    private final VaultRepository vaultRepository;
    private final LinkRewriteNotifier notifier;
    private final Clock clock;

    public LinkRewriteService(DocumentLinkRepository linkRepository, LinkRewriteRepository rewriteRepository,
                               DocumentRepository documentRepository, VaultRepository vaultRepository,
                               LinkRewriteNotifier notifier, Clock clock) {
        this.linkRepository = linkRepository;
        this.rewriteRepository = rewriteRepository;
        this.documentRepository = documentRepository;
        this.vaultRepository = vaultRepository;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * Queues a link fix in every note that pointed at {@code oldPath} in this vault.
     *
     * @return how many notes need fixing
     */
    @Override
    @Transactional
    public int onDocumentRenamed(UUID documentId, UUID vaultId, String oldPath, String newPath) {
        Optional<String> slug = vaultRepository.findById(vaultId).map(vault -> vault.getSlug());
        if (slug.isEmpty()) {
            // A vault without a namespace cannot be linked to from outside, so nothing can break.
            return 0;
        }
        String normalizedOld = WikiLinks.normalizeTarget(oldPath);
        List<DocumentLinkEntity> inbound = linkRepository.findByTargetVaultSlugAndTargetPath(slug.get(), normalizedOld);
        if (inbound.isEmpty()) {
            return 0;
        }

        int queued = 0;
        for (DocumentLinkEntity link : inbound) {
            String newLink = WikiLinks.rewriteTarget(link.getLinkText(), slug.get(), newPath);
            if (newLink.equals(link.getLinkText())) {
                continue;
            }
            LinkRewriteEntity rewrite = rewriteRepository.save(
                    new LinkRewriteEntity(link.getSourceDocumentId(), link.getLinkText(), newLink, clock.instant()));
            String sourcePath = documentRepository.findById(link.getSourceDocumentId())
                    .map(DocumentEntity::getCurrentPath)
                    .orElse("");
            notifier.notifyLinkRewrite(link.getSourceVaultId(), link.getSourceDocumentId(), sourcePath,
                    rewrite.getId(), rewrite.getOldLink(), rewrite.getNewLink());
            queued++;
        }
        AppLog.info("Queued {} cross-vault link rewrite(s) after renaming '{}' to '{}'", queued, oldPath, newPath);
        return queued;
    }

    /** Instructions a client should apply when it opens (or already has) this document. */
    public List<LinkRewriteEntity> pendingFor(UUID documentId) {
        return rewriteRepository.findByDocumentIdAndAppliedAtIsNullOrderByIdAsc(documentId);
    }

    /**
     * Marks an instruction as done. Idempotent on purpose: two clients may have the note open and
     * both report the same rewrite, and the second report must not be an error.
     */
    @Transactional
    public void markApplied(UUID documentId, long rewriteId) {
        rewriteRepository.findById(rewriteId)
                .filter(rewrite -> rewrite.getDocumentId().equals(documentId))
                .filter(rewrite -> rewrite.getAppliedAt() == null)
                .ifPresent(rewrite -> {
                    rewrite.markApplied(clock.instant());
                    rewriteRepository.save(rewrite);
                });
    }

    /** The vault a document belongs to - used by the controller to check access before answering. */
    public Optional<DocumentEntity> document(UUID documentId) {
        return documentRepository.findById(documentId);
    }
}
