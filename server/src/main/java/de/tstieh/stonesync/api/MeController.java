package de.tstieh.stonesync.api;

import de.tstieh.stonesync.admin.SystemRole;
import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.admin.UserVaultAccessRepository;
import de.tstieh.stonesync.admin.VaultRepository;
import de.tstieh.stonesync.admin.VaultRole;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * "Who am I and what am I a member of" - the first call a freshly configured plugin makes, so it
 * can show the user their role instead of finding out by being refused later.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserRepository userRepository;
    private final UserVaultAccessRepository accessRepository;
    private final VaultRepository vaultRepository;

    public MeController(UserRepository userRepository, UserVaultAccessRepository accessRepository,
                         VaultRepository vaultRepository) {
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.vaultRepository = vaultRepository;
    }

    @GetMapping
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(new MeResponse(user.getId(), user.getEmail(), user.getSystemRole(),
                        vaultsOf(user))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private List<VaultMembership> vaultsOf(UserEntity user) {
        return accessRepository.findByUserId(user.getId()).stream()
                .map(access -> new VaultMembership(access.getVaultId(),
                        vaultRepository.findById(access.getVaultId()).map(vault -> vault.getName()).orElse("(unknown)"),
                        access.getRole()))
                .toList();
    }

    public record MeResponse(UUID userId, String email, SystemRole systemRole, List<VaultMembership> vaults) {
    }

    public record VaultMembership(UUID vaultId, String name, VaultRole role) {
    }
}
