package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.access.AccessLevel;
import de.tstieh.stonesync.access.PathRules;
import de.tstieh.stonesync.access.Permission;
import de.tstieh.stonesync.access.VaultPathRuleEntity;
import de.tstieh.stonesync.access.VaultPathRuleRepository;
import de.tstieh.stonesync.logging.AppLog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Central authorization for everything scoped to a vault (documents, attachments, the sync
 * WebSocket, member management). An authenticated API key only proves *who* a caller is - it
 * says nothing about *what* they may do, and since path rules exist, not even about which notes
 * inside a vault they may see.
 *
 * <p>Three inputs decide an effective {@link AccessLevel}:</p>
 * <ol>
 *   <li>the account's {@link SystemRole} - an ADMIN is OWNER everywhere;</li>
 *   <li>the vault membership role (none at all means no access);</li>
 *   <li>path rules that override that role for a subtree (see {@link PathRules}).</li>
 * </ol>
 *
 * <p>Every controller/handler that touches a vault or a document must go through
 * {@link #requireVaultPermission} or {@link #requirePathPermission} before doing anything else.</p>
 */
@Service
public class VaultAccessService {

    private final UserVaultAccessRepository accessRepository;
    private final VaultPathRuleRepository pathRuleRepository;
    private final UserRepository userRepository;

    public VaultAccessService(UserVaultAccessRepository accessRepository,
                               VaultPathRuleRepository pathRuleRepository,
                               UserRepository userRepository) {
        this.accessRepository = accessRepository;
        this.pathRuleRepository = pathRuleRepository;
        this.userRepository = userRepository;
    }

    /** The user's level for the vault as a whole, ignoring path rules. */
    public AccessLevel vaultLevel(UUID userId, UUID vaultId) {
        if (isSystemAdmin(userId)) {
            return AccessLevel.OWNER;
        }
        return accessRepository.findByUserIdAndVaultId(userId, vaultId)
                .map(access -> AccessLevel.of(access.getRole()))
                .orElse(AccessLevel.NONE);
    }

    /** The user's level for one specific note/attachment path inside a vault. */
    public AccessLevel pathLevel(UUID userId, UUID vaultId, String path) {
        AccessLevel base = vaultLevel(userId, vaultId);
        if (base == AccessLevel.NONE || isSystemAdmin(userId)) {
            // No membership at all is never rescued by a path rule, and an admin is never
            // restricted by one - both short-circuit before any rule is even read.
            return base;
        }
        // A blanket "everyone" rule never applies to a vault owner: the owner is the one who
        // creates such rules to keep a folder private *from others*, and locking the person
        // responsible for the vault out of its content (including its history and restores)
        // would be a trap rather than a feature. A rule aimed at that owner by name still counts.
        List<PathRules.PathRule> rules = base == AccessLevel.OWNER
                ? rulesFor(vaultId).stream().filter(rule -> rule.userId() != null).toList()
                : rulesFor(vaultId);
        return PathRules.resolve(rules, userId, path, base);
    }

    /** All rules of a vault that could apply to this user (their own plus the everyone-rules). */
    public List<PathRules.PathRule> rulesFor(UUID vaultId, UUID userId) {
        return pathRuleRepository.findByVaultId(vaultId).stream()
                .filter(rule -> rule.getUserId() == null || rule.getUserId().equals(userId))
                .map(VaultPathRuleEntity::toRule)
                .toList();
    }

    private List<PathRules.PathRule> rulesFor(UUID vaultId) {
        return pathRuleRepository.findByVaultId(vaultId).stream()
                .map(VaultPathRuleEntity::toRule)
                .toList();
    }

    public boolean isSystemAdmin(UUID userId) {
        return userRepository.findById(userId).map(UserEntity::isSystemAdmin).orElse(false);
    }

    public boolean canRead(UUID userId, UUID vaultId, String path) {
        return pathLevel(userId, vaultId, path).allows(Permission.READ);
    }

    public boolean canWrite(UUID userId, UUID vaultId, String path) {
        return pathLevel(userId, vaultId, path).allows(Permission.WRITE);
    }

    public void requireVaultPermission(UUID userId, UUID vaultId, Permission permission) {
        AccessLevel level = vaultLevel(userId, vaultId);
        if (!level.allows(permission)) {
            AppLog.warn("Denied {}: user {} has level {} on vault {}", permission, userId, level, vaultId);
            throw new VaultAccessDeniedException(
                    "User " + userId + " may not " + permission + " on vault " + vaultId);
        }
        AppLog.debug("Access check passed: user {} may {} on vault {}", userId, permission, vaultId);
    }

    public void requirePathPermission(UUID userId, UUID vaultId, String path, Permission permission) {
        AccessLevel level = pathLevel(userId, vaultId, path);
        if (!level.allows(permission)) {
            AppLog.warn("Denied {}: user {} has level {} on '{}' in vault {}", permission, userId, level, path, vaultId);
            throw new VaultAccessDeniedException(
                    "User " + userId + " may not " + permission + " '" + path + "' in vault " + vaultId);
        }
        AppLog.debug("Access check passed: user {} may {} '{}' in vault {}", userId, permission, path, vaultId);
    }

    /** Convenience for the many read paths: "may this user see this vault at all". */
    public boolean hasAccess(UUID userId, UUID vaultId) {
        return vaultLevel(userId, vaultId).allows(Permission.READ);
    }

    /** @deprecated use {@link #requireVaultPermission} with an explicit {@link Permission}. */
    @Deprecated
    public void requireAccess(UUID userId, UUID vaultId) {
        requireVaultPermission(userId, vaultId, Permission.READ);
    }
}
