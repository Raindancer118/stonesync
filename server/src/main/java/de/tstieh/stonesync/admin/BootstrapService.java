package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.auth.ApiKeyHasher;
import de.tstieh.stonesync.logging.AppLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

/**
 * Solves the chicken-and-egg problem at first startup: every {@code /api/admin/**} endpoint
 * requires a Bearer API key, but the very first API key can only be minted through one of
 * those endpoints. If {@code stonesync.bootstrap.admin-email} is set and no user exists yet,
 * this creates exactly one admin user, a default vault, OWNER access to it, and one API key -
 * once. As soon as any user exists, this becomes a permanent no-op, so it is safe to leave
 * the property set across restarts.
 */
@Service
public class BootstrapService {

    private final UserRepository userRepository;
    private final AdminService adminService;
    private final BootstrapProperties properties;
    private final SecureRandom random = new SecureRandom();

    public BootstrapService(UserRepository userRepository, AdminService adminService, BootstrapProperties properties) {
        this.userRepository = userRepository;
        this.adminService = adminService;
        this.properties = properties;
    }

    @Transactional
    public Optional<BootstrapResult> runIfNeeded() {
        if (!properties.isEnabled()) {
            AppLog.debug("Bootstrap skipped: stonesync.bootstrap.admin-email not set");
            return Optional.empty();
        }
        if (userRepository.count() > 0) {
            AppLog.debug("Bootstrap skipped: users already exist");
            return Optional.empty();
        }

        AppLog.info("Running first-time bootstrap for admin email '{}'", properties.adminEmail());
        UserEntity user = adminService.createUser(properties.adminEmail(), randomPasswordHash());
        VaultEntity vault = adminService.createVault(properties.vaultName(), user.getId());
        adminService.grantAccess(user.getId(), vault.getId(), VaultRole.OWNER);
        String rawApiKey = adminService.createApiKey(user.getId(), properties.deviceName());
        AppLog.info("Bootstrap complete: user {}, vault {}", user.getId(), vault.getId());

        return Optional.of(new BootstrapResult(user.getId(), vault.getId(), rawApiKey));
    }

    /**
     * Password login is not implemented anywhere in this server (auth is exclusively via API
     * keys, see SecurityConfig) - {@code users.password_hash} is NOT NULL for future use, so a
     * random, never-disclosed value is stored rather than a real credential.
     */
    private String randomPasswordHash() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return new ApiKeyHasher().hash(java.util.Base64.getEncoder().encodeToString(bytes));
    }

    public record BootstrapResult(UUID userId, UUID vaultId, String rawApiKey) {
    }
}
