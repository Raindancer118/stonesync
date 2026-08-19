package de.tstieh.stonesync.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "user_vault_access")
public class UserVaultAccessEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "vault_id", nullable = false)
    private UUID vaultId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VaultRole role;

    protected UserVaultAccessEntity() {
        // JPA
    }

    public UserVaultAccessEntity(UUID id, UUID userId, UUID vaultId, VaultRole role) {
        this.id = id;
        this.userId = userId;
        this.vaultId = vaultId;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getVaultId() {
        return vaultId;
    }

    public VaultRole getRole() {
        return role;
    }

    public void changeRole(VaultRole role) {
        this.role = role;
    }
}
