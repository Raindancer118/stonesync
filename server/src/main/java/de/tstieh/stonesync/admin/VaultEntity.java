package de.tstieh.stonesync.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vaults")
public class VaultEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Namespace used to link into this vault from another one ({@code [[sales:Note]]}). Optional:
     * a vault without a slug simply cannot be linked to from outside, which is the safe default.
     */
    @Column
    private String slug;

    protected VaultEntity() {
        // JPA
    }

    public VaultEntity(UUID id, String name, UUID ownerId, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getSlug() {
        return slug;
    }

    public void changeSlug(String slug) {
        this.slug = slug;
    }
}
