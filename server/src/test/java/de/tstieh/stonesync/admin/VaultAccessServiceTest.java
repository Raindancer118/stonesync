package de.tstieh.stonesync.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultAccessServiceTest {

    @Mock
    private UserVaultAccessRepository accessRepository;

    private VaultAccessService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();

    @Test
    @DisplayName("hasAccess is true when a user_vault_access row exists")
    void hasAccessTrueWhenRowExists() {
        service = new VaultAccessService(accessRepository);
        when(accessRepository.findByUserIdAndVaultId(userId, vaultId))
                .thenReturn(Optional.of(new UserVaultAccessEntity(UUID.randomUUID(), userId, vaultId, VaultRole.EDITOR)));

        assertThat(service.hasAccess(userId, vaultId)).isTrue();
    }

    @Test
    @DisplayName("hasAccess is false without a user_vault_access row")
    void hasAccessFalseWithoutRow() {
        service = new VaultAccessService(accessRepository);
        when(accessRepository.findByUserIdAndVaultId(userId, vaultId)).thenReturn(Optional.empty());

        assertThat(service.hasAccess(userId, vaultId)).isFalse();
    }

    @Test
    @DisplayName("requireAccess throws VaultAccessDeniedException when the user has no access to the vault (prevents IDOR)")
    void requireAccessThrowsWhenDenied() {
        service = new VaultAccessService(accessRepository);
        when(accessRepository.findByUserIdAndVaultId(userId, vaultId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireAccess(userId, vaultId))
                .isInstanceOf(VaultAccessDeniedException.class);
    }

    @Test
    @DisplayName("requireAccess throws nothing when the user has access")
    void requireAccessPassesWhenGranted() {
        service = new VaultAccessService(accessRepository);
        when(accessRepository.findByUserIdAndVaultId(userId, vaultId))
                .thenReturn(Optional.of(new UserVaultAccessEntity(UUID.randomUUID(), userId, vaultId, VaultRole.VIEWER)));

        service.requireAccess(userId, vaultId);
    }
}
