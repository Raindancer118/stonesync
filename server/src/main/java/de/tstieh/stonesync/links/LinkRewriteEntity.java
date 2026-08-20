package de.tstieh.stonesync.links;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending "replace this link text with that one" instruction for one document.
 *
 * <p>The server deliberately does not produce the edit itself: it understands no Yjs, so it
 * states the intent and a client - whichever has the document open, or the next one to open it -
 * performs it as an ordinary edit that then flows to everyone through the normal sync path.</p>
 */
@Entity
@Table(name = "link_rewrites")
public class LinkRewriteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "old_link", nullable = false)
    private String oldLink;

    @Column(name = "new_link", nullable = false)
    private String newLink;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    protected LinkRewriteEntity() {
        // JPA
    }

    public LinkRewriteEntity(UUID documentId, String oldLink, String newLink, Instant createdAt) {
        this.documentId = documentId;
        this.oldLink = oldLink;
        this.newLink = newLink;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getOldLink() {
        return oldLink;
    }

    public String getNewLink() {
        return newLink;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void markApplied(Instant when) {
        this.appliedAt = when;
    }
}
