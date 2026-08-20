package de.tstieh.stonesync.invite;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Builds the {@code obsidian://} deep link handed to a freshly onboarded collaborator after a
 * successful invite login - the plugin registers a handler for the {@code stonesync-connect}
 * action (see {@code onboarding/DeepLinkHandler.ts}) that reads these params, writes them into
 * its settings, and kicks off the initial vault download.
 */
public final class DeepLinkBuilder {

    private DeepLinkBuilder() {
    }

    public static String build(String serverUrl, String apiKey, UUID vaultId, String displayName) {
        return "obsidian://stonesync-connect?"
                + "serverUrl=" + encode(serverUrl)
                + "&apiKey=" + encode(apiKey)
                + "&vaultId=" + vaultId
                + "&displayName=" + encode(displayName);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
