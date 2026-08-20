package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.access.AccessLevel;
import de.tstieh.stonesync.access.Permission;
import de.tstieh.stonesync.access.VaultPathRuleEntity;
import de.tstieh.stonesync.access.VaultPathRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultAccessServiceTest {

    @Mock
    private UserVaultAccessRepository accessRepository;
    @Mock
    private VaultPathRuleRepository pathRuleRepository;
    @Mock
    private UserRepository userRepository;

    private VaultAccessService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VaultAccessService(accessRepository, pathRuleRepository, userRepository);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(ordinaryUser()));
        lenient().when(pathRuleRepository.findByVaultId(vaultId)).thenReturn(List.of());
    }

    private UserEntity ordinaryUser() {
        return new UserEntity(userId, "user@example.com", "hash", Instant.EPOCH);
    }

    private UserEntity adminUser() {
        UserEntity user = new UserEntity(userId, "admin@example.com", "hash", Instant.EPOCH);
        user.changeSystemRole(SystemRole.ADMIN);
        return user;
    }

    private void membership(VaultRole role) {
        when(accessRepository.findByUserIdAndVaultId(userId, vaultId))
                .thenReturn(Optional.of(new UserVaultAccessEntity(UUID.randomUUID(), userId, vaultId, role)));
    }

    private void noMembership() {
        when(accessRepository.findByUserIdAndVaultId(userId, vaultId)).thenReturn(Optional.empty());
    }

    /**
     * Lenient on purpose: for a system admin and for a non-member the service must never even
     * look at the rules, so these stubs staying unused is part of what the tests assert.
     */
    private void rules(VaultPathRuleEntity... entities) {
        lenient().when(pathRuleRepository.findByVaultId(vaultId)).thenReturn(List.of(entities));
    }

    private VaultPathRuleEntity rule(String prefix, UUID forUser, AccessLevel level) {
        return new VaultPathRuleEntity(UUID.randomUUID(), vaultId, prefix, forUser, level, Instant.EPOCH, null);
    }

    @Test
    @DisplayName("without a membership row nothing is allowed (prevents IDOR)")
    void noMembershipMeansNoAccess() {
        noMembership();

        assertThat(service.vaultLevel(userId, vaultId)).isEqualTo(AccessLevel.NONE);
        assertThatThrownBy(() -> service.requireVaultPermission(userId, vaultId, Permission.READ))
                .isInstanceOf(VaultAccessDeniedException.class);
    }

    @Test
    @DisplayName("a VIEWER may read but is refused every write")
    void viewerIsReadOnly() {
        membership(VaultRole.VIEWER);

        assertThatCode(() -> service.requirePathPermission(userId, vaultId, "Notes/x.md", Permission.READ))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requirePathPermission(userId, vaultId, "Notes/x.md", Permission.WRITE))
                .isInstanceOf(VaultAccessDeniedException.class);
    }

    @Test
    @DisplayName("an EDITOR may write content but not manage members")
    void editorMayWriteButNotManage() {
        membership(VaultRole.EDITOR);

        assertThat(service.canWrite(userId, vaultId, "Notes/x.md")).isTrue();
        assertThatThrownBy(() -> service.requireVaultPermission(userId, vaultId, Permission.MANAGE_MEMBERS))
                .isInstanceOf(VaultAccessDeniedException.class);
    }

    @Test
    @DisplayName("a path rule hides a subtree from a member who otherwise has full access")
    void pathRuleTakesAccessAway() {
        membership(VaultRole.EDITOR);
        rules(rule("Privat", null, AccessLevel.NONE));

        assertThat(service.canRead(userId, vaultId, "Privat/diary.md")).isFalse();
        assertThat(service.canRead(userId, vaultId, "Shared/plan.md")).isTrue();
    }

    @Test
    @DisplayName("a path rule can also make a read-only member an editor in one folder")
    void pathRuleCanElevate() {
        membership(VaultRole.VIEWER);
        rules(rule("Team", userId, AccessLevel.EDITOR));

        assertThat(service.canWrite(userId, vaultId, "Team/plan.md")).isTrue();
        assertThat(service.canWrite(userId, vaultId, "Other/plan.md")).isFalse();
    }

    @Test
    @DisplayName("a path rule never grants management rights - those come from the vault role only")
    void pathRuleNeverGrantsManagement() {
        membership(VaultRole.EDITOR);
        rules(rule("Team", userId, AccessLevel.OWNER));

        assertThat(service.pathLevel(userId, vaultId, "Team/x.md")).isEqualTo(AccessLevel.OWNER);
        assertThatThrownBy(() -> service.requireVaultPermission(userId, vaultId, Permission.MANAGE_MEMBERS))
                .isInstanceOf(VaultAccessDeniedException.class);
    }

    @Test
    @DisplayName("a system admin is owner everywhere, without any membership row")
    void systemAdminHasAccessEverywhere() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(adminUser()));

        assertThat(service.vaultLevel(userId, vaultId)).isEqualTo(AccessLevel.OWNER);
        assertThatCode(() -> service.requireVaultPermission(userId, vaultId, Permission.MANAGE_VAULT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a system admin is not restricted by a path rule either")
    void systemAdminIgnoresPathRules() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(adminUser()));
        rules(rule("Privat", null, AccessLevel.NONE));

        assertThat(service.canRead(userId, vaultId, "Privat/diary.md")).isTrue();
    }

    @Test
    @DisplayName("a path rule cannot rescue someone who is not a member of the vault at all")
    void ruleDoesNotRescueNonMember() {
        noMembership();
        rules(rule("Team", userId, AccessLevel.EDITOR));

        assertThat(service.canRead(userId, vaultId, "Team/plan.md")).isFalse();
    }
}
