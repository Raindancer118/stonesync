package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.audit.AuditEventType;
import de.tstieh.stonesync.audit.AuditService;
import de.tstieh.stonesync.auth.ApiKeyEntity;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import de.tstieh.stonesync.auth.ApiKeyRepository;
import de.tstieh.stonesync.history.VaultGitRepository;
import de.tstieh.stonesync.logging.AppLog;
import de.tstieh.stonesync.sync.DocumentDeletionBroadcaster;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.DocumentRepository;
import de.tstieh.stonesync.sync.VaultEventBroadcaster;
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
 * <p>By default, deletes are guarded rather than cascading: a vault is only removable once it
 * has no documents left (see {@link VaultNotEmptyException}), and a user is only removable once
 * they own no vaults (see {@link UserOwnsVaultsException}) - both would otherwise risk silently
 * destroying synced content or orphaning a vault. {@link #deleteVault(UUID, boolean)}'s
 * {@code force} path is the deliberate exception to that guard.</p>
 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final VaultRepository vaultRepository;
    private final UserVaultAccessRepository accessRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final DocumentRepository documentRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final AuditService auditService;
    private final Clock clock;
    private final VaultGitRepository gitRepository;
    private final DocumentDeletionBroadcaster documentDeletionBroadcaster;
    private final VaultEventBroadcaster vaultEventBroadcaster;

    public AdminService(UserRepository userRepository, VaultRepository vaultRepository,
                         UserVaultAccessRepository accessRepository, ApiKeyRepository apiKeyRepository,
                         DocumentRepository documentRepository, ApiKeyHasher apiKeyHasher,
                         AuditService auditService, Clock clock,
                         VaultGitRepository gitRepository, DocumentDeletionBroadcaster documentDeletionBroadcaster,
                         VaultEventBroadcaster vaultEventBroadcaster) {
        this.userRepository = userRepository;
        this.vaultRepository = vaultRepository;
        this.accessRepository = accessRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.documentRepository = documentRepository;
        this.apiKeyHasher = apiKeyHasher;
        this.auditService = auditService;
        this.clock = clock;
        this.gitRepository = gitRepository;
        this.documentDeletionBroadcaster = documentDeletionBroadcaster;
        this.vaultEventBroadcaster = vaultEventBroadcaster;
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
        grantAccess(userId, vaultId, role, null);
    }

    /** @param actorId who performed the change, for the audit trail ({@code null} = admin key/system). */
    @Transactional
    public void grantAccess(UUID userId, UUID vaultId, VaultRole role, UUID actorId) {
        accessRepository.findByUserIdAndVaultId(userId, vaultId)
                .ifPresentOrElse(
                        existing -> {
                            VaultRole previous = existing.getRole();
                            existing.changeRole(role);
                            auditService.recordAccessChange(AuditEventType.ACCESS_ROLE_CHANGED, actorId, vaultId, userId,
                                    previous + " -> " + role);
                            AppLog.info("Changed user {} to role {} on vault {}", userId, role, vaultId);
                        },
                        () -> {
                            accessRepository.save(new UserVaultAccessEntity(UUID.randomUUID(), userId, vaultId, role));
                            auditService.recordAccessChange(AuditEventType.ACCESS_GRANTED, actorId, vaultId, userId,
                                    role.name());
                            AppLog.info("Granted user {} role {} on vault {}", userId, role, vaultId);
                        });
    }

    /** Promotes/demotes an account-wide {@link SystemRole}. */
    @Transactional
    public void changeSystemRole(UUID userId, SystemRole role, UUID actorId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user: " + userId));
        SystemRole previous = user.getSystemRole();
        user.changeSystemRole(role);
        userRepository.save(user);
        auditService.recordAccessChange(AuditEventType.SYSTEM_ROLE_CHANGED, actorId, null, userId,
                previous + " -> " + role);
        AppLog.info("Changed system role of user {} from {} to {}", userId, previous, role);
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
        revokeAccess(userId, vaultId, null);
    }

    /** @param actorId who performed the change, for the audit trail ({@code null} = admin key/system). */
    @Transactional
    public void revokeAccess(UUID userId, UUID vaultId, UUID actorId) {
        Optional<UserVaultAccessEntity> access = accessRepository.findByUserIdAndVaultId(userId, vaultId);
        if (access.isPresent()) {
            accessRepository.delete(access.get());
            auditService.recordAccessChange(AuditEventType.ACCESS_REVOKED, actorId, vaultId, userId,
                    access.get().getRole().name());
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
        deleteVault(vaultId, false);
    }

    /**
     * @param force When {@code false} (the default, see {@link #deleteVault(UUID)}), refuses to
     *              delete a vault that still has document rows at all - including already
     *              soft-deleted ones, since {@code documentRepository.findByVaultId} is not
     *              filtered by {@code deleted_at}. When {@code true}, hard-deletes every document
     *              row - cascading at the database level (see migration
     *              {@code V9__cascade_document_deletes.sql}) to everything referencing them: Yjs
     *              updates/snapshots, attachments, pending restores, cross-vault links, link
     *              rewrites - plus the vault's entire git history repository, then the vault
     *              itself. Explicit product decision: an
     *              operator who reaches for {@code --force} has already decided the content
     *              doesn't matter (e.g. a soft-deleted-everything test vault) - this is not
     *              guarded further.
     *
     *              <p>Deliberately does NOT delete the underlying attachment blob files on disk:
     *              they are content-addressed (SHA-256) and deduplicated *across all vaults*, so a
     *              blob this vault's attachment rows point at may still be referenced by a
     *              completely different vault. Leaving orphaned blobs behind is a cheap, safe
     *              cost; deleting one still in use elsewhere would not be.</p>
     */
    @Transactional
    public void deleteVault(UUID vaultId, boolean force) {
        List<DocumentEntity> documents = documentRepository.findByVaultId(vaultId);
        if (!documents.isEmpty() && !force) {
            AppLog.warn("Refused to delete vault {} - it still has documents", vaultId);
            throw new VaultNotEmptyException("Vault " + vaultId + " still has documents - delete those first");
        }

        if (!documents.isEmpty()) {
            List<UUID> documentIds = documents.stream().map(DocumentEntity::getId).toList();

            // Kick every connected client BEFORE touching a single row: a still-connected client
            // sending a real edit for one of these documents right as we delete it could otherwise
            // still land after the fact. We don't wait for or care what was in flight - notify,
            // disconnect, then delete. `notifyVaultDeleted` tells every vault-events session
            // "vault_deleted" before closing it, which is the "whoops, your vault is gone" the
            // client surfaces.
            documentIds.forEach(documentDeletionBroadcaster::kickSessions);
            vaultEventBroadcaster.notifyVaultDeleted(vaultId);

            // A single cascading delete (see migration V9__cascade_document_deletes.sql), not one
            // pre-delete per referencing table: an app-level, multiple-statements-in-a-fixed-order
            // approach turned out to NOT be race-free in production - a still-connected client's
            // live edit landing between an early pre-delete and this statement re-inserted a row
            // that this statement's own foreign key check then tripped over. One atomic DELETE has
            // no such window; anything a client sends after this point simply fails its own insert
            // against an already-gone parent document, on that write's own request, not this one.
            documentRepository.deleteAllByIdInBatch(documentIds);
            gitRepository.deleteRepository(vaultId);
            AppLog.warn("Force-deleted vault {} - hard-removed {} document row(s) and its git history", vaultId, documentIds.size());
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
