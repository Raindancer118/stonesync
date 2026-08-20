package de.tstieh.stonesync.links;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Obsidian wiki links out of Markdown, and separates the two kinds that matter here:
 *
 * <ul>
 *   <li><b>local</b> {@code [[Note]]} - Obsidian's own business. The server indexes them not at
 *       all and never rewrites them: a vault has to keep working as a plain Obsidian vault with
 *       no server reachable, and Obsidian already maintains these links itself on rename.</li>
 *   <li><b>cross-vault</b> {@code [[slug:Path/Note]]} - only these need a server, because no
 *       client can resolve another vault's contents on its own.</li>
 * </ul>
 *
 * A namespace is only recognised as such if it looks like a slug (lowercase letters, digits and
 * dashes) - so {@code [[C:/notes/thing]]} or {@code [[Meeting: Q3]]} stay ordinary local links.
 */
public final class WikiLinks {

    /** [[ target ( #heading )? ( |alias )? ]] - heading and alias are kept out of the target. */
    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\[\\]]+?)]]");
    private static final Pattern NAMESPACED = Pattern.compile("^([a-z0-9][a-z0-9-]{0,62}):(.+)$");

    private WikiLinks() {
    }

    public static List<CrossVaultLink> crossVaultLinks(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return List.of();
        }
        List<CrossVaultLink> links = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = LINK.matcher(markdown);
        while (matcher.find()) {
            String inner = matcher.group(1);
            String target = inner.split("\\|", 2)[0].split("#", 2)[0].trim();
            Matcher namespaced = NAMESPACED.matcher(target);
            if (!namespaced.matches()) {
                continue; // a plain local link - not ours to touch
            }
            String linkText = "[[" + inner + "]]";
            if (seen.add(linkText)) {
                links.add(new CrossVaultLink(namespaced.group(1), normalizeTarget(namespaced.group(2)), linkText));
            }
        }
        return links;
    }

    /** Whether this exact link text points at the given vault/path pair. */
    public static boolean pointsAt(CrossVaultLink link, String slug, String path) {
        return link.vaultSlug().equals(slug) && link.targetPath().equals(normalizeTarget(path));
    }

    /**
     * Rewrites one link's target while keeping everything the author wrote around it - alias,
     * heading anchor and spacing all survive, because a rename must not silently change how a
     * note reads.
     */
    public static String rewriteTarget(String linkText, String newSlug, String newPath) {
        Matcher matcher = LINK.matcher(linkText);
        if (!matcher.matches()) {
            return linkText;
        }
        String inner = matcher.group(1);
        String aliasPart = inner.contains("|") ? "|" + inner.split("\\|", 2)[1] : "";
        String withoutAlias = inner.split("\\|", 2)[0];
        String headingPart = withoutAlias.contains("#") ? "#" + withoutAlias.split("#", 2)[1] : "";
        return "[[" + newSlug + ":" + stripExtension(newPath) + headingPart + aliasPart + "]]";
    }

    /** Links are written without the .md extension, the way Obsidian writes them. */
    public static String normalizeTarget(String path) {
        String trimmed = path.trim().replaceAll("^/+", "");
        return stripExtension(trimmed);
    }

    private static String stripExtension(String path) {
        return path.endsWith(".md") ? path.substring(0, path.length() - 3) : path;
    }

    /** One cross-vault link occurrence: where it points, and the literal text it was written as. */
    public record CrossVaultLink(String vaultSlug, String targetPath, String linkText) {
    }
}
