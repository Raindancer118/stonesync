package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.auth.ApiKeyEntity;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/users")
    public UserResponse createUser(@RequestBody CreateUserRequest request) {
        UserEntity user = adminService.createUser(request.email(), request.passwordHash());
        return toResponse(user);
    }

    @GetMapping("/users")
    public List<UserResponse> listUsers() {
        return adminService.listUsers().stream().map(this::toResponse).toList();
    }

    @PostMapping("/vaults")
    public VaultResponse createVault(@RequestBody CreateVaultRequest request) {
        VaultEntity vault = adminService.createVault(request.name(), request.ownerId());
        return new VaultResponse(vault.getId(), vault.getName(), vault.getOwnerId());
    }

    @GetMapping("/vaults")
    public List<VaultResponse> listVaults() {
        return adminService.listVaults().stream()
                .map(v -> new VaultResponse(v.getId(), v.getName(), v.getOwnerId()))
                .toList();
    }

    @PostMapping("/vaults/{vaultId}/access")
    public void grantAccess(@PathVariable UUID vaultId, @RequestBody GrantAccessRequest request) {
        adminService.grantAccess(request.userId(), vaultId, request.role());
    }

    @PostMapping("/users/{userId}/api-keys")
    public ApiKeyResponse createApiKey(@PathVariable UUID userId, @RequestBody CreateApiKeyRequest request) {
        String rawKey = adminService.createApiKey(userId, request.deviceName());
        return new ApiKeyResponse(rawKey);
    }

    @GetMapping("/users/{userId}/api-keys")
    public List<ApiKeyListEntry> listApiKeys(@PathVariable UUID userId) {
        return adminService.listApiKeys(userId).stream()
                .map(k -> new ApiKeyListEntry(k.getId(), k.getName(), k.getCreatedAt(), k.getRevokedAt()))
                .toList();
    }

    @DeleteMapping("/api-keys/{apiKeyId}")
    public void revokeApiKey(@PathVariable UUID apiKeyId) {
        adminService.revokeApiKey(apiKeyId);
    }

    private UserResponse toResponse(UserEntity user) {
        return new UserResponse(user.getId(), user.getEmail());
    }

    public record CreateUserRequest(@NotBlank String email, @NotBlank String passwordHash) {
    }

    public record UserResponse(UUID id, String email) {
    }

    public record CreateVaultRequest(@NotBlank String name, UUID ownerId) {
    }

    public record VaultResponse(UUID id, String name, UUID ownerId) {
    }

    public record GrantAccessRequest(UUID userId, VaultRole role) {
    }

    public record CreateApiKeyRequest(@NotBlank String deviceName) {
    }

    public record ApiKeyResponse(String rawKey) {
    }

    public record ApiKeyListEntry(UUID id, String name, Instant createdAt, Instant revokedAt) {
    }
}
