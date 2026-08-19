package de.tstieh.stonesync.admin;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Central authorization check for everything scoped to a vault (documents, attachments, the
 * sync WebSocket). An authenticated API key only proves *who* a caller is - it says nothing
 * about *which* vaults they may touch. Every controller/handler that operates on a vault or a
 * document must call {@link #requireAccess} before doing anything else.
 */
@Service
public class VaultAccessService {

    private final UserVaultAccessRepository accessRepository;

    public VaultAccessService(UserVaultAccessRepository accessRepository) {
        this.accessRepository = accessRepository;
    }

    public boolean hasAccess(UUID userId, UUID vaultId) {
        return accessRepository.findByUserIdAndVaultId(userId, vaultId).isPresent();
    }

    public void requireAccess(UUID userId, UUID vaultId) {
        if (!hasAccess(userId, vaultId)) {
            throw new VaultAccessDeniedException("User " + userId + " has no access to vault " + vaultId);
        }
    }
}
