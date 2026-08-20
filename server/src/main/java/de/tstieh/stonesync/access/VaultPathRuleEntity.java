package de.tstieh.stonesync.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One path-scoped access rule (see {@link PathRules} for how rules are resolved against each
 * other). {@code userId == null} means the rule applies to everyone with access to the vault.
 */
@Entity
@Table(name = "vault_path_rules")
public class VaultPathRuleEntity {

    @Id
    private UUID id;

    @Column(name = "vault_id", nullable = false)
    private UUID vaultId;

    @Column(name = "path_prefix", nullable = false)
    private String pathPrefix;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccessLevel level;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    protected VaultPathRuleEntity() {
        // JPA
    }

    public VaultPathRuleEntity(UUID id, UUID vaultId, String pathPrefix, UUID userId, AccessLevel level,
                                Instant createdAt, UUID createdBy) {
        this.id = id;
        this.vaultId = vaultId;
        this.pathPrefix = PathRules.normalize(pathPrefix);
        this.userId = userId;
        this.level = level;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVaultId() {
        return vaultId;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public UUID getUserId() {
        return userId;
    }

    public AccessLevel getLevel() {
        return level;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void changeLevel(AccessLevel level) {
        this.level = level;
    }

    public PathRules.PathRule toRule() {
        return new PathRules.PathRule(pathPrefix, userId, level);
    }
}
