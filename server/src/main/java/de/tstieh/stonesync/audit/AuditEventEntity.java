package de.tstieh.stonesync.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One append-only entry of the audit trail. Never updated, never deleted by the application. */
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditEventType type;

    @Column(name = "actor_id")
    private UUID actorId;

    /** Human-readable actor (email, or "system"/"admin-key") - kept even if the user is deleted. */
    @Column(name = "actor_label", nullable = false)
    private String actorLabel;

    @Column(name = "vault_id")
    private UUID vaultId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column
    private String path;

    /** The user a permission change was about, if any. */
    @Column(name = "subject_id")
    private UUID subjectId;

    @Column
    private String detail;

    protected AuditEventEntity() {
        // JPA
    }

    public AuditEventEntity(Instant occurredAt, AuditEventType type, UUID actorId, String actorLabel, UUID vaultId,
                             UUID documentId, String path, UUID subjectId, String detail) {
        this.occurredAt = occurredAt;
        this.type = type;
        this.actorId = actorId;
        this.actorLabel = actorLabel;
        this.vaultId = vaultId;
        this.documentId = documentId;
        this.path = path;
        this.subjectId = subjectId;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public AuditEventType getType() {
        return type;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public UUID getVaultId() {
        return vaultId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getPath() {
        return path;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getDetail() {
        return detail;
    }
}
