package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.auth.ApiKeyEntity;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import de.tstieh.stonesync.auth.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private VaultRepository vaultRepository;
    @Mock
    private UserVaultAccessRepository accessRepository;
    @Mock
    private ApiKeyRepository apiKeyRepository;

    private final ApiKeyHasher hasher = new ApiKeyHasher();
    private AdminService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new AdminService(userRepository, vaultRepository, accessRepository, apiKeyRepository, hasher, clock);
    }

    @Test
    @DisplayName("a newly created API key is stored hashed, the plaintext is returned only once")
    void createApiKeyStoresOnlyTheHash() {
        String rawKey = service.createApiKey(userId, "my-device");

        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(apiKeyRepository).save(captor.capture());

        assertThat(captor.getValue().getKeyHash()).isEqualTo(hasher.hash(rawKey));
        assertThat(captor.getValue().getKeyHash()).isNotEqualTo(rawKey);
    }

    @Test
    @DisplayName("revoking an API key sets revoked_at")
    void revokeApiKeySetsRevokedAt() {
        ApiKeyEntity key = new ApiKeyEntity(UUID.randomUUID(), userId, "dev", "hash", Instant.now());
        when(apiKeyRepository.findById(key.getId())).thenReturn(Optional.of(key));

        service.revokeApiKey(key.getId());

        assertThat(key.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("assigning a vault role creates a new access entry on first access")
    void grantAccessCreatesNewEntryOnFirstAssignment() {
        UUID vaultId = UUID.randomUUID();
        when(accessRepository.findByUserIdAndVaultId(userId, vaultId)).thenReturn(Optional.empty());

        service.grantAccess(userId, vaultId, VaultRole.EDITOR);

        verify(accessRepository).save(any(UserVaultAccessEntity.class));
    }
}
