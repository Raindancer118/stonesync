package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.auth.ApiKeyEntity;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import de.tstieh.stonesync.auth.ApiKeyRepository;
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
        return userRepository.save(user);
    }

    public List<UserEntity> listUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public VaultEntity createVault(String name, UUID ownerId) {
        VaultEntity vault = new VaultEntity(UUID.randomUUID(), name, ownerId, clock.instant());
        return vaultRepository.save(vault);
    }

    public List<VaultEntity> listVaults() {
        return vaultRepository.findAll();
    }

    @Transactional
    public void grantAccess(UUID userId, UUID vaultId, VaultRole role) {
        accessRepository.findByUserIdAndVaultId(userId, vaultId)
                .ifPresentOrElse(
                        existing -> existing.changeRole(role),
                        () -> accessRepository.save(new UserVaultAccessEntity(UUID.randomUUID(), userId, vaultId, role)));
    }

    /** Creates a new API key/device for a user. Returns the raw key - shown to the caller only once. */
    @Transactional
    public String createApiKey(UUID userId, String deviceName) {
        String rawKey = apiKeyHasher.generateRawKey();
        ApiKeyEntity entity = new ApiKeyEntity(UUID.randomUUID(), userId, deviceName,
                apiKeyHasher.hash(rawKey), clock.instant());
        apiKeyRepository.save(entity);
        return rawKey;
    }

    public List<ApiKeyEntity> listApiKeys(UUID userId) {
        return apiKeyRepository.findByUserId(userId);
    }

    @Transactional
    public void revokeApiKey(UUID apiKeyId) {
        ApiKeyEntity entity = apiKeyRepository.findById(apiKeyId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown api key: " + apiKeyId));
        entity.revoke(clock.instant());
        apiKeyRepository.save(entity);
    }

    /** Idempotent: removing a grant that doesn't exist is a no-op, matching DELETE semantics. */
    @Transactional
    public void revokeAccess(UUID userId, UUID vaultId) {
        Optional<UserVaultAccessEntity> access = accessRepository.findByUserIdAndVaultId(userId, vaultId);
        access.ifPresent(accessRepository::delete);
    }

    /**
     * Only removable once empty (see class javadoc) - deletes its access grants first, since
     * those are pure permission records with no content of their own.
     */
    @Transactional
    public void deleteVault(UUID vaultId) {
        if (!documentRepository.findByVaultId(vaultId).isEmpty()) {
            throw new VaultNotEmptyException("Vault " + vaultId + " still has documents - delete those first");
        }
        accessRepository.deleteAll(accessRepository.findByVaultId(vaultId));
        vaultRepository.deleteById(vaultId);
    }

    /**
     * Only removable once they own no vaults (see class javadoc) - deletes their API keys and
     * access grants first, since those are pure credential/permission records with no content
     * of their own.
     */
    @Transactional
    public void deleteUser(UUID userId) {
        if (!vaultRepository.findByOwnerId(userId).isEmpty()) {
            throw new UserOwnsVaultsException("User " + userId + " still owns vaults - transfer or delete those first");
        }
        apiKeyRepository.deleteAll(apiKeyRepository.findByUserId(userId));
        accessRepository.deleteAll(accessRepository.findByUserId(userId));
        userRepository.deleteById(userId);
    }
}
