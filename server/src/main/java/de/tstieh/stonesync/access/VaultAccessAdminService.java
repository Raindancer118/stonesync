package de.tstieh.stonesync.access;

import de.tstieh.stonesync.admin.AdminService;
import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.admin.UserVaultAccessEntity;
import de.tstieh.stonesync.admin.UserVaultAccessRepository;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.admin.VaultRole;
import de.tstieh.stonesync.audit.AuditEventType;
import de.tstieh.stonesync.audit.AuditService;
import de.tstieh.stonesync.logging.AppLog;
import de.tstieh.stonesync.sync.DocumentService;
import de.tstieh.stonesync.sync.VaultEventBroadcaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Self-service permission management for a vault's owner - the same operations the admin API
 * offers, but authorized by {@link Permission#MANAGE_MEMBERS} on that one vault instead of by
 * the server-wide admin key.
 *
 * <p>Every mutation goes through {@link #applyAndNotify}, which compares what each affected user
 * could read before and after the change and pushes an {@code access_revoked} event for every
 * note that just became invisible to them. Without that, taking access away would only stop
 * future updates while the existing copy quietly stayed on their device.</p>
 */
@Service
public class VaultAccessAdminService {

    private final VaultAccessService vaultAccessService;
    private final UserVaultAccessRepository accessRepository;
    private final VaultPathRuleRepository pathRuleRepository;
    private final UserRepository userRepository;
    private final DocumentService documentService;
    private final AdminService adminService;
    private final AuditService auditService;
    private final VaultEventBroadcaster vaultEventBroadcaster;
    private final Clock clock;

    public VaultAccessAdminService(VaultAccessService vaultAccessService, UserVaultAccessRepository accessRepository,
                                    VaultPathRuleRepository pathRuleRepository, UserRepository userRepository,
                                    DocumentService documentService, AdminService adminService,
                                    AuditService auditService, VaultEventBroadcaster vaultEventBroadcaster,
                                    Clock clock) {
        this.vaultAccessService = vaultAccessService;
        this.accessRepository = accessRepository;
        this.pathRuleRepository = pathRuleRepository;
        this.userRepository = userRepository;
        this.documentService = documentService;
        this.adminService = adminService;
        this.auditService = auditService;
        this.vaultEventBroadcaster = vaultEventBroadcaster;
        this.clock = clock;
    }

    public List<Member> listMembers(UUID actorId, UUID vaultId) {
        vaultAccessService.requireVaultPermission(actorId, vaultId, Permission.MANAGE_MEMBERS);
        return accessRepository.findByVaultId(vaultId).stream()
                .map(access -> new Member(access.getUserId(), emailOf(access.getUserId()), access.getRole()))
                .sorted((a, b) -> a.email().compareToIgnoreCase(b.email()))
                .toList();
    }

    @Transactional
    public void setMemberRole(UUID actorId, UUID vaultId, UUID memberId, VaultRole role) {
        vaultAccessService.requireVaultPermission(actorId, vaultId, Permission.MANAGE_MEMBERS);
        applyAndNotify(vaultId, List.of(memberId), () -> {
            adminService.grantAccess(memberId, vaultId, role, actorId);
            return null;
        });
    }

    @Transactional
    public void removeMember(UUID actorId, UUID vaultId, UUID memberId) {
        vaultAccessService.requireVaultPermission(actorId, vaultId, Permission.MANAGE_MEMBERS);
        if (actorId.equals(memberId)) {
            // Removing yourself would leave a vault that nobody can manage any more.
            throw new IllegalArgumentException("You cannot remove your own access to a vault you manage");
        }
        applyAndNotify(vaultId, List.of(memberId), () -> {
            adminService.revokeAccess(memberId, vaultId, actorId);
            return null;
        });
    }

    public List<Rule> listRules(UUID actorId, UUID vaultId) {
        vaultAccessService.requireVaultPermission(actorId, vaultId, Permission.MANAGE_MEMBERS);
        return pathRuleRepository.findByVaultId(vaultId).stream()
                .map(rule -> new Rule(rule.getId(), rule.getPathPrefix(), rule.getUserId(),
                        rule.getUserId() == null ? null : emailOf(rule.getUserId()), rule.getLevel()))
                .sorted((a, b) -> a.pathPrefix().compareToIgnoreCase(b.pathPrefix()))
                .toList();
    }

    /** Creates or updates the rule for (vault, prefix, user) - {@code memberId == null} means everyone. */
    @Transactional
    public Rule setRule(UUID actorId, UUID vaultId, String pathPrefix, UUID memberId, AccessLevel level) {
        vaultAccessService.requireVaultPermission(actorId, vaultId, Permission.MANAGE_MEMBERS);
        String normalized = PathRules.normalize(pathPrefix);
        List<UUID> affected = memberId != null ? List.of(memberId) : memberIds(vaultId);

        return applyAndNotify(vaultId, affected, () -> {
            Optional<VaultPathRuleEntity> existing = memberId == null
                    ? pathRuleRepository.findByVaultIdAndPathPrefixAndUserIdIsNull(vaultId, normalized)
                    : pathRuleRepository.findByVaultIdAndPathPrefixAndUserId(vaultId, normalized, memberId);
            VaultPathRuleEntity entity = existing.orElseGet(() -> new VaultPathRuleEntity(UUID.randomUUID(), vaultId,
                    normalized, memberId, level, clock.instant(), actorId));
            entity.changeLevel(level);
            pathRuleRepository.save(entity);
            auditService.record(AuditEventType.PATH_RULE_SET, actorId, vaultId, null, normalized, memberId,
                    level.name());
            AppLog.info("Set path rule '{}' = {} for {} in vault {}", normalized, level,
                    memberId == null ? "everyone" : memberId, vaultId);
            return new Rule(entity.getId(), entity.getPathPrefix(), entity.getUserId(),
                    entity.getUserId() == null ? null : emailOf(entity.getUserId()), entity.getLevel());
        });
    }

    @Transactional
    public void removeRule(UUID actorId, UUID vaultId, UUID ruleId) {
        vaultAccessService.requireVaultPermission(actorId, vaultId, Permission.MANAGE_MEMBERS);
        VaultPathRuleEntity rule = pathRuleRepository.findById(ruleId)
                .filter(candidate -> candidate.getVaultId().equals(vaultId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown rule " + ruleId + " for vault " + vaultId));
        List<UUID> affected = rule.getUserId() != null ? List.of(rule.getUserId()) : memberIds(vaultId);

        applyAndNotify(vaultId, affected, () -> {
            pathRuleRepository.delete(rule);
            auditService.record(AuditEventType.PATH_RULE_REMOVED, actorId, vaultId, null, rule.getPathPrefix(),
                    rule.getUserId(), rule.getLevel().name());
            AppLog.info("Removed path rule '{}' in vault {}", rule.getPathPrefix(), vaultId);
            return null;
        });
    }

    /** What the calling user themselves may do in this vault - the plugin's read-only switch. */
    public MyPermissions permissionsOf(UUID userId, UUID vaultId) {
        vaultAccessService.requireVaultPermission(userId, vaultId, Permission.READ);
        List<PathRules.PathRule> rules = vaultAccessService.rulesFor(vaultId, userId);
        return new MyPermissions(
                vaultAccessService.vaultLevel(userId, vaultId),
                rules.stream().map(rule -> new EffectiveRule(rule.pathPrefix(), rule.level())).toList());
    }

    /**
     * Runs a permission change and tells every affected user about the notes they just lost -
     * computed as the difference between what they could read before and after.
     */
    private <T> T applyAndNotify(UUID vaultId, List<UUID> affectedUsers, Supplier<T> change) {
        List<DocumentService.DocumentSummary> documents = documentService.listNonDeletedForRestore(vaultId);
        Map<UUID, List<DocumentService.DocumentSummary>> readableBefore = readableFor(affectedUsers, vaultId, documents);

        T result = change.get();

        for (UUID userId : affectedUsers) {
            for (DocumentService.DocumentSummary document : readableBefore.getOrDefault(userId, List.of())) {
                if (!vaultAccessService.canRead(userId, vaultId, document.path())) {
                    vaultEventBroadcaster.notifyAccessRevoked(vaultId, userId, document.id(), document.path());
                }
            }
        }
        return result;
    }

    private Map<UUID, List<DocumentService.DocumentSummary>> readableFor(List<UUID> users, UUID vaultId,
                                                                          List<DocumentService.DocumentSummary> documents) {
        Map<UUID, List<DocumentService.DocumentSummary>> readable = new java.util.HashMap<>();
        for (UUID userId : users) {
            List<DocumentService.DocumentSummary> visible = new ArrayList<>();
            for (DocumentService.DocumentSummary document : documents) {
                if (vaultAccessService.canRead(userId, vaultId, document.path())) {
                    visible.add(document);
                }
            }
            readable.put(userId, visible);
        }
        return readable;
    }

    private List<UUID> memberIds(UUID vaultId) {
        return accessRepository.findByVaultId(vaultId).stream().map(UserVaultAccessEntity::getUserId).toList();
    }

    private String emailOf(UUID userId) {
        return userRepository.findById(userId).map(UserEntity::getEmail).orElse(userId.toString());
    }

    public record Member(UUID userId, String email, VaultRole role) {
    }

    public record Rule(UUID id, String pathPrefix, UUID userId, String email, AccessLevel level) {
    }

    public record EffectiveRule(String pathPrefix, AccessLevel level) {
    }

    public record MyPermissions(AccessLevel vaultLevel, List<EffectiveRule> rules) {
    }
}
