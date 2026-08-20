package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.auth.ApiKeyEntity;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import de.tstieh.stonesync.auth.ApiKeyRepository;
import de.tstieh.stonesync.logging.AppLog;
import de.tstieh.stonesync.sync.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * User/device/vault administration - create, list, delete, plus access-role assignment/revoke.
 *
 * <p>Deletes are deliberately guarded rather than cascading: a vault is only removable once
 * it has no documents left (see {@link VaultNotEmptyException}), and a user is only removable
 * once they own no vaults (see {@link UserOwnsVaultsException}) - both would otherwise risk
 * silently destroying synced content or orphaning a vault.</p>
 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final VaultRepository vaultRepository;
    private final UserVaultAccessRepository accessRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final DocumentRepository documentRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final Clock clock;

    public AdminService(UserRepository userRepository, VaultRepository vaultRepository,
                         UserVaultAccessRepository accessRepository, ApiKeyRepository apiKeyRepository,
                         DocumentRepository documentRepository, ApiKeyHasher apiKeyHasher, Clock clock) {
        this.userRepository = userRepository;
        this.vaultRepository = vaultRepository;
        this.accessRepository = accessRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.documentRepository = documentRepository;
        this.apiKeyHasher = apiKeyHasher;
        this.clock = clock;
    }

    @Transactional
    public UserEntity createUser(String email, String passwordHash) {
        UserEntity user = new UserEntity(UUID.randomUUID(), email, passwordHash, clock.instant());
        userRepository.save(user);
        AppLog.info("Created user {} ({})", user.getId(), email);
        return user;
    }

    public List<UserEntity> listUsers() {
        List<UserEntity> users = userRepository.findAll();
        AppLog.debug("Listed {} users", users.size());
        return users;
    }

    @Transactional
    public VaultEntity createVault(String name, UUID ownerId) {
        VaultEntity vault = new VaultEntity(UUID.randomUUID(), name, ownerId, clock.instant());
        vaultRepository.save(vault);
        AppLog.info("Created vault {} ('{}') owned by user {}", vault.getId(), name, ownerId);
        return vault;
    }

    public List<VaultEntity> listVaults() {
        List<VaultEntity> vaults = vaultRepository.findAll();
        AppLog.debug("Listed {} vaults", vaults.size());
        return vaults;
    }

    @Transactional
    public void grantAccess(UUID userId, UUID vaultId, VaultRole role) {
        accessRepository.findByUserIdAndVaultId(userId, vaultId)
                .ifPresentOrElse(
                        existing -> {
                            existing.changeRole(role);
                            AppLog.info("Changed user {} to role {} on vault {}", userId, role, vaultId);
                        },
                        () -> {
                            accessRepository.save(new UserVaultAccessEntity(UUID.randomUUID(), userId, vaultId, role));
                            AppLog.info("Granted user {} role {} on vault {}", userId, role, vaultId);
                        });
    }

    /** Creates a new API key/device for a user. Returns the raw key - shown to the caller only once. */
    @Transactional
    public String createApiKey(UUID userId, String deviceName) {
        String rawKey = apiKeyHasher.generateRawKey();
        ApiKeyEntity entity = new ApiKeyEntity(UUID.randomUUID(), userId, deviceName,
                apiKeyHasher.hash(rawKey), clock.instant());
        apiKeyRepository.save(entity);
        AppLog.info("Created API key {} ('{}') for user {}", entity.getId(), deviceName, userId);
        return rawKey;
    }

    public List<ApiKeyEntity> listApiKeys(UUID userId) {
        List<ApiKeyEntity> keys = apiKeyRepository.findByUserId(userId);
        AppLog.debug("Listed {} API keys for user {}", keys.size(), userId);
        return keys;
    }

    @Transactional
    public void revokeApiKey(UUID apiKeyId) {
        ApiKeyEntity entity = apiKeyRepository.findById(apiKeyId)
                .orElseThrow(() -> {
                    AppLog.warn("Attempted to revoke unknown API key {}", apiKeyId);
                    return new IllegalArgumentException("Unknown api key: " + apiKeyId);
                });
        entity.revoke(clock.instant());
        apiKeyRepository.save(entity);
        AppLog.info("Revoked API key {} (user {})", apiKeyId, entity.getUserId());
    }

    /** Idempotent: removing a grant that doesn't exist is a no-op, matching DELETE semantics. */
    @Transactional
    public void revokeAccess(UUID userId, UUID vaultId) {
        Optional<UserVaultAccessEntity> access = accessRepository.findByUserIdAndVaultId(userId, vaultId);
        if (access.isPresent()) {
            accessRepository.delete(access.get());
            AppLog.info("Revoked user {}'s access to vault {}", userId, vaultId);
        } else {
            AppLog.debug("No-op: user {} already had no access to vault {}", userId, vaultId);
        }
    }

    /**
     * Only removable once empty (see class javadoc) - deletes its access grants first, since
     * those are pure permission records with no content of their own.
     */
    @Transactional
    public void deleteVault(UUID vaultId) {
        if (!documentRepository.findByVaultId(vaultId).isEmpty()) {
            AppLog.warn("Refused to delete vault {} - it still has documents", vaultId);
            throw new VaultNotEmptyException("Vault " + vaultId + " still has documents - delete those first");
        }
        accessRepository.deleteAll(accessRepository.findByVaultId(vaultId));
        vaultRepository.deleteById(vaultId);
        AppLog.info("Deleted vault {}", vaultId);
    }

    /**
     * Only removable once they own no vaults (see class javadoc) - deletes their API keys and
     * access grants first, since those are pure credential/permission records with no content
     * of their own.
     */
    @Transactional
    public void deleteUser(UUID userId) {
        if (!vaultRepository.findByOwnerId(userId).isEmpty()) {
            AppLog.warn("Refused to delete user {} - they still own vaults", userId);
            throw new UserOwnsVaultsException("User " + userId + " still owns vaults - transfer or delete those first");
        }
        apiKeyRepository.deleteAll(apiKeyRepository.findByUserId(userId));
        accessRepository.deleteAll(accessRepository.findByUserId(userId));
        userRepository.deleteById(userId);
        AppLog.info("Deleted user {}", userId);
    }
}
