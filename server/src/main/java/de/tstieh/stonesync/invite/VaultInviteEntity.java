package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.VaultRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vault_invites")
public class VaultInviteEntity {

    @Id
    private UUID id;

    @Column(name = "vault_id", nullable = false)
    private UUID vaultId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VaultRole role;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected VaultInviteEntity() {
        // JPA
    }

    public VaultInviteEntity(UUID id, UUID vaultId, VaultRole role, String tokenHash, UUID createdBy,
                              Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.vaultId = vaultId;
        this.role = role;
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVaultId() {
        return vaultId;
    }

    public VaultRole getRole() {
        return role;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void markConsumed(Instant now) {
        this.consumedAt = now;
    }
}
