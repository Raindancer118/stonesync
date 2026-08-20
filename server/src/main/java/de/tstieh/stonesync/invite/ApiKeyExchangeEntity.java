package de.tstieh.stonesync.invite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_key_exchanges")
public class ApiKeyExchangeEntity {

    @Id
    private UUID id;

    @Column(name = "code_hash", nullable = false, unique = true)
    private String codeHash;

    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @Column(name = "vault_id", nullable = false)
    private UUID vaultId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected ApiKeyExchangeEntity() {
        // JPA
    }

    public ApiKeyExchangeEntity(UUID id, String codeHash, String apiKey, UUID vaultId, String displayName,
                                 Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.codeHash = codeHash;
        this.apiKey = apiKey;
        this.vaultId = vaultId;
        this.displayName = displayName;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public String getApiKey() {
        return apiKey;
    }

    public UUID getVaultId() {
        return vaultId;
    }

    public String getDisplayName() {
        return displayName;
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
