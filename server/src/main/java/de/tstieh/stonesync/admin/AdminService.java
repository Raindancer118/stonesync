package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.auth.ApiKeyEntity;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import de.tstieh.stonesync.auth.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** User/device/vault administration - create, list, and revoke, plus access-role assignment. */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final VaultRepository vaultRepository;
    private final UserVaultAccessRepository accessRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final Clock clock;

    public AdminService(UserRepository userRepository, VaultRepository vaultRepository,
                         UserVaultAccessRepository accessRepository, ApiKeyRepository apiKeyRepository,
                         ApiKeyHasher apiKeyHasher, Clock clock) {
        this.userRepository = userRepository;
        this.vaultRepository = vaultRepository;
        this.accessRepository = accessRepository;
        this.apiKeyRepository = apiKeyRepository;
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
}
