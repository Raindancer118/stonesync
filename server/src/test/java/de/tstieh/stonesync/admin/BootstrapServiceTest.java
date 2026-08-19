package de.tstieh.stonesync.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AdminService adminService;

    @Test
    @DisplayName("bootstrap disabled (no adminEmail configured) creates nothing")
    void doesNothingWhenDisabled() {
        BootstrapProperties properties = new BootstrapProperties("", "Default Vault", "bootstrap");
        BootstrapService service = new BootstrapService(userRepository, adminService, properties);

        Optional<BootstrapService.BootstrapResult> result = service.runIfNeeded();

        assertThat(result).isEmpty();
        verify(adminService, never()).createUser(any(), any());
    }

    @Test
    @DisplayName("bootstrap skips when at least one user already exists")
    void skipsWhenUsersAlreadyExist() {
        BootstrapProperties properties = new BootstrapProperties("admin@example.com", "Default Vault", "bootstrap");
        when(userRepository.count()).thenReturn(1L);
        BootstrapService service = new BootstrapService(userRepository, adminService, properties);

        Optional<BootstrapService.BootstrapResult> result = service.runIfNeeded();

        assertThat(result).isEmpty();
        verify(adminService, never()).createUser(any(), any());
    }

    @Test
    @DisplayName("bootstrap creates admin user, vault, owner access and API key when the user table is empty")
    void createsAdminUserVaultAndApiKeyWhenEmpty() {
        BootstrapProperties properties = new BootstrapProperties("admin@example.com", "Default Vault", "bootstrap");
        when(userRepository.count()).thenReturn(0L);

        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity(userId, "admin@example.com", "irrelevant-hash", java.time.Instant.now());
        when(adminService.createUser(org.mockito.ArgumentMatchers.eq("admin@example.com"), any())).thenReturn(user);

        UUID vaultId = UUID.randomUUID();
        VaultEntity vault = new VaultEntity(vaultId, "Default Vault", userId, java.time.Instant.now());
        when(adminService.createVault("Default Vault", userId)).thenReturn(vault);

        when(adminService.createApiKey(userId, "bootstrap")).thenReturn("raw-api-key-value");

        BootstrapService service = new BootstrapService(userRepository, adminService, properties);
        Optional<BootstrapService.BootstrapResult> result = service.runIfNeeded();

        assertThat(result).isPresent();
        assertThat(result.get().rawApiKey()).isEqualTo("raw-api-key-value");
        assertThat(result.get().userId()).isEqualTo(userId);
        assertThat(result.get().vaultId()).isEqualTo(vaultId);

        verify(adminService).grantAccess(userId, vaultId, VaultRole.OWNER);
    }
}
