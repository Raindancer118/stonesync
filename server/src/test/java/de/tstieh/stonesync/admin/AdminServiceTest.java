package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.auth.ApiKeyEntity;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import de.tstieh.stonesync.auth.ApiKeyRepository;
import de.tstieh.stonesync.history.VaultGitRepository;
import de.tstieh.stonesync.sync.DocumentDeletionBroadcaster;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentRepository;
import de.tstieh.stonesync.sync.VaultEventBroadcaster;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private VaultGitRepository gitRepository;
    @Mock
    private DocumentDeletionBroadcaster documentDeletionBroadcaster;
    @Mock
    private VaultEventBroadcaster vaultEventBroadcaster;

    private final ApiKeyHasher hasher = new ApiKeyHasher();
    @org.mockito.Mock
    private de.tstieh.stonesync.audit.AuditService auditService;

    private AdminService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new AdminService(userRepository, vaultRepository, accessRepository, apiKeyRepository,
                documentRepository, hasher, auditService, clock,
                gitRepository, documentDeletionBroadcaster, vaultEventBroadcaster);
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

    @Test
    @DisplayName("deleting a vault with existing documents is rejected, to prevent losing synced content")
    void deleteVaultWithDocumentsIsRejected() {
        UUID vaultId = UUID.randomUUID();
        when(documentRepository.findByVaultId(vaultId)).thenReturn(List.of(
                new DocumentEntity(UUID.randomUUID(), vaultId, "note.md", DocumentEntity.ContentType.TEXT, Instant.now())));

        assertThatThrownBy(() -> service.deleteVault(vaultId)).isInstanceOf(VaultNotEmptyException.class);

        verify(vaultRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("force-deleting a vault with documents kicks connections, hard-removes the documents "
            + "(their referencing rows cascade at the database level), erases its git history, and the vault itself")
    void forceDeleteVaultWithDocumentsRemovesEverything() {
        UUID vaultId = UUID.randomUUID();
        DocumentEntity document = new DocumentEntity(UUID.randomUUID(), vaultId, "note.md",
                DocumentEntity.ContentType.TEXT, Instant.now());
        when(documentRepository.findByVaultId(vaultId)).thenReturn(List.of(document));
        List<UUID> documentIds = List.of(document.getId());

        service.deleteVault(vaultId, true);

        verify(documentDeletionBroadcaster).kickSessions(document.getId());
        verify(vaultEventBroadcaster).notifyVaultDeleted(vaultId);
        verify(documentRepository).deleteAllByIdInBatch(documentIds);
        verify(gitRepository).deleteRepository(vaultId);
        verify(vaultRepository).deleteById(vaultId);
    }

    @Test
    @DisplayName("deleting an empty vault removes its access grants and the vault itself")
    void deleteEmptyVaultRemovesAccessGrantsAndVault() {
        UUID vaultId = UUID.randomUUID();
        when(documentRepository.findByVaultId(vaultId)).thenReturn(List.of());
        UserVaultAccessEntity access = new UserVaultAccessEntity(UUID.randomUUID(), userId, vaultId, VaultRole.OWNER);
        when(accessRepository.findByVaultId(vaultId)).thenReturn(List.of(access));

        service.deleteVault(vaultId);

        verify(accessRepository).deleteAll(List.of(access));
        verify(vaultRepository).deleteById(vaultId);
    }

    @Test
    @DisplayName("deleting a user who still owns vaults is rejected, to avoid orphaning them")
    void deleteUserWhoOwnsVaultsIsRejected() {
        when(vaultRepository.findByOwnerId(userId)).thenReturn(List.of(
                new VaultEntity(UUID.randomUUID(), "My Vault", userId, Instant.now())));

        assertThatThrownBy(() -> service.deleteUser(userId)).isInstanceOf(UserOwnsVaultsException.class);

        verify(userRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("deleting a user with no owned vaults removes their API keys, access grants and the user")
    void deleteUserRemovesApiKeysAccessGrantsAndUser() {
        when(vaultRepository.findByOwnerId(userId)).thenReturn(List.of());
        ApiKeyEntity key = new ApiKeyEntity(UUID.randomUUID(), userId, "dev", "hash", Instant.now());
        when(apiKeyRepository.findByUserId(userId)).thenReturn(List.of(key));
        UserVaultAccessEntity access = new UserVaultAccessEntity(UUID.randomUUID(), userId, UUID.randomUUID(), VaultRole.EDITOR);
        when(accessRepository.findByUserId(userId)).thenReturn(List.of(access));

        service.deleteUser(userId);

        verify(apiKeyRepository).deleteAll(List.of(key));
        verify(accessRepository).deleteAll(List.of(access));
        verify(userRepository).deleteById(userId);
    }

    @Test
    @DisplayName("revoking access for a user/vault pair that has no grant is a no-op, not an error (idempotent DELETE)")
    void revokeAccessIsIdempotentWhenNoGrantExists() {
        UUID vaultId = UUID.randomUUID();
        when(accessRepository.findByUserIdAndVaultId(userId, vaultId)).thenReturn(Optional.empty());

        service.revokeAccess(userId, vaultId);

        verify(accessRepository, never()).delete(any());
    }

    @Test
    @DisplayName("revoking an existing access grant removes it")
    void revokeAccessRemovesExistingGrant() {
        UUID vaultId = UUID.randomUUID();
        UserVaultAccessEntity access = new UserVaultAccessEntity(UUID.randomUUID(), userId, vaultId, VaultRole.VIEWER);
        when(accessRepository.findByUserIdAndVaultId(userId, vaultId)).thenReturn(Optional.of(access));

        service.revokeAccess(userId, vaultId);

        verify(accessRepository).delete(access);
    }
}
