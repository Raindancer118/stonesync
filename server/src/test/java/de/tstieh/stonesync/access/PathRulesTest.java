package de.tstieh.stonesync.access;

import de.tstieh.stonesync.access.PathRules.PathRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PathRulesTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Nested
    @DisplayName("prefix matching")
    class Matching {

        @Test
        @DisplayName("a folder rule covers the folder itself and everything below it")
        void coversSubtree() {
            assertThat(PathRules.matches("Team", "Team")).isTrue();
            assertThat(PathRules.matches("Team", "Team/notes.md")).isTrue();
            assertThat(PathRules.matches("Team", "Team/sub/deep.md")).isTrue();
        }

        @Test
        @DisplayName("matching is per path segment, so 'Team' never covers 'Teamwork.md'")
        void doesNotMatchPartialSegments() {
            assertThat(PathRules.matches("Team", "Teamwork.md")).isFalse();
            assertThat(PathRules.matches("Team", "OtherTeam/x.md")).isFalse();
        }

        @Test
        @DisplayName("surrounding slashes are irrelevant")
        void normalizesSlashes() {
            assertThat(PathRules.matches("/Team/", "Team/x.md")).isTrue();
            assertThat(PathRules.matches("Team", "/Team/x.md")).isTrue();
        }

        @Test
        @DisplayName("the empty prefix is a vault-wide rule")
        void emptyPrefixMatchesEverything() {
            assertThat(PathRules.matches("", "anything/at/all.md")).isTrue();
        }
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("without any rule the vault membership level applies")
        void fallsBackToMembership() {
            assertThat(PathRules.resolve(List.of(), alice, "Notes/x.md", AccessLevel.EDITOR))
                    .isEqualTo(AccessLevel.EDITOR);
        }

        @Test
        @DisplayName("a rule can take access away entirely - the private-folder case")
        void ruleCanRevokeAccess() {
            List<PathRule> rules = List.of(new PathRule("Privat", null, AccessLevel.NONE));

            assertThat(PathRules.resolve(rules, bob, "Privat/diary.md", AccessLevel.EDITOR)).isEqualTo(AccessLevel.NONE);
            assertThat(PathRules.resolve(rules, bob, "Shared/notes.md", AccessLevel.EDITOR)).isEqualTo(AccessLevel.EDITOR);
        }

        @Test
        @DisplayName("a user-specific rule beats the everyone-rule on the same folder")
        void userRuleBeatsEveryoneRule() {
            List<PathRule> rules = List.of(
                    new PathRule("Privat", null, AccessLevel.NONE),
                    new PathRule("Privat", alice, AccessLevel.OWNER));

            assertThat(PathRules.resolve(rules, alice, "Privat/diary.md", AccessLevel.VIEWER)).isEqualTo(AccessLevel.OWNER);
            assertThat(PathRules.resolve(rules, bob, "Privat/diary.md", AccessLevel.VIEWER)).isEqualTo(AccessLevel.NONE);
        }

        @Test
        @DisplayName("the deeper rule wins over the shallower one")
        void longestPrefixWins() {
            List<PathRule> rules = List.of(
                    new PathRule("Team", null, AccessLevel.EDITOR),
                    new PathRule("Team/Payroll", null, AccessLevel.NONE));

            assertThat(PathRules.resolve(rules, bob, "Team/plan.md", AccessLevel.VIEWER)).isEqualTo(AccessLevel.EDITOR);
            assertThat(PathRules.resolve(rules, bob, "Team/Payroll/2026.md", AccessLevel.VIEWER)).isEqualTo(AccessLevel.NONE);
        }

        @Test
        @DisplayName("a rule can also grant more than the vault role - read-only member, editor in one folder")
        void ruleCanElevate() {
            List<PathRule> rules = List.of(new PathRule("Team", bob, AccessLevel.EDITOR));

            assertThat(PathRules.resolve(rules, bob, "Team/plan.md", AccessLevel.VIEWER)).isEqualTo(AccessLevel.EDITOR);
            assertThat(PathRules.resolve(rules, bob, "Elsewhere.md", AccessLevel.VIEWER)).isEqualTo(AccessLevel.VIEWER);
        }

        @Test
        @DisplayName("rules addressed at someone else are ignored")
        void ignoresOtherUsersRules() {
            List<PathRule> rules = List.of(new PathRule("Privat", alice, AccessLevel.OWNER));

            assertThat(PathRules.resolve(rules, bob, "Privat/diary.md", AccessLevel.VIEWER)).isEqualTo(AccessLevel.VIEWER);
        }
    }

    @Nested
    @DisplayName("level semantics")
    class Levels {

        @Test
        @DisplayName("a viewer may read but not write")
        void viewerIsReadOnly() {
            assertThat(AccessLevel.VIEWER.allows(Permission.READ)).isTrue();
            assertThat(AccessLevel.VIEWER.allows(Permission.WRITE)).isFalse();
        }

        @Test
        @DisplayName("no access allows nothing at all")
        void noneAllowsNothing() {
            for (Permission permission : Permission.values()) {
                assertThat(AccessLevel.NONE.allows(permission)).isFalse();
            }
        }

        @Test
        @DisplayName("only a vault owner manages members and the vault itself")
        void managementIsOwnerOnly() {
            assertThat(AccessLevel.EDITOR.allows(Permission.MANAGE_MEMBERS)).isFalse();
            assertThat(AccessLevel.OWNER.allows(Permission.MANAGE_MEMBERS)).isTrue();
            assertThat(AccessLevel.OWNER.allows(Permission.MANAGE_VAULT)).isTrue();
        }
    }
}
