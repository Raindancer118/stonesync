package de.tstieh.stonesync.access;

import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;

/**
 * Pure resolution logic for path-scoped access rules, kept free of Spring and JPA so the
 * (security-critical) precedence rules can be tested exhaustively in isolation.
 *
 * <p>Precedence, most specific first:</p>
 * <ol>
 *   <li>the longest matching path prefix wins - a rule on {@code Team/Secret} beats one on {@code Team};</li>
 *   <li>at equal length, a rule for this specific user beats the everyone-rule;</li>
 *   <li>if nothing matches, the user's vault membership level applies.</li>
 * </ol>
 */
public final class PathRules {

    private PathRules() {
    }

    /**
     * A prefix matches a path if it is the path itself or a parent folder of it. Matching is on
     * whole segments: {@code Team} must not match {@code Teamwork.md}. The empty prefix matches
     * everything (a vault-wide rule).
     */
    public static boolean matches(String prefix, String path) {
        String normalizedPrefix = normalize(prefix);
        String normalizedPath = normalize(path);
        if (normalizedPrefix.isEmpty()) {
            return true;
        }
        return normalizedPath.equals(normalizedPrefix) || normalizedPath.startsWith(normalizedPrefix + "/");
    }

    /** Strips leading/trailing slashes so "/Team/" and "Team" are the same rule. */
    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * The level that applies to {@code path}, or {@code fallback} when no rule matches.
     *
     * @param userId the user the decision is for; rules addressed at other users are ignored
     */
    public static AccessLevel resolve(Collection<PathRule> rules, UUID userId, String path, AccessLevel fallback) {
        return decidingRule(rules, userId, path).map(PathRule::level).orElse(fallback);
    }

    /**
     * The rule that actually decides {@code path} for this user, if any. Exposed separately so a
     * UI can explain *why* someone has the access they have ("inherited from the rule on Team/")
     * instead of only showing the outcome.
     */
    public static java.util.Optional<PathRule> decidingRule(Collection<PathRule> rules, UUID userId, String path) {
        return rules.stream()
                .filter(rule -> rule.appliesTo(userId))
                .filter(rule -> matches(rule.pathPrefix(), path))
                .max(Comparator
                        .comparingInt((PathRule rule) -> normalize(rule.pathPrefix()).length())
                        .thenComparingInt(rule -> rule.userId() != null ? 1 : 0));
    }

    /** One rule, decoupled from its JPA entity. A {@code null} userId means "everyone". */
    public record PathRule(String pathPrefix, UUID userId, AccessLevel level) {
        boolean appliesTo(UUID candidate) {
            return userId == null || userId.equals(candidate);
        }
    }
}
