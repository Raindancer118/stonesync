package de.tstieh.stonesync.invite;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The server's own externally-visible base URL (e.g. {@code https://stonesync.tstieh.de}),
 * needed to build absolute invite links and the deep-link back into Obsidian. Deliberately an
 * explicit, required config value rather than derived from the incoming request: behind a
 * reverse proxy (NPMplus), request-derived URL construction is unreliable unless every
 * X-Forwarded-* header is set up correctly, and a silently wrong deep link would be far worse
 * than a clear startup-time configuration requirement.
 */
@ConfigurationProperties(prefix = "stonesync.public")
public record PublicUrlProperties(String url) {

    public String requireUrl() {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "PUBLIC_URL is not configured - required for invite links and the Obsidian deep-link redirect");
        }
        return url.replaceAll("/+$", "");
    }
}
