package de.tstieh.stonesync.invite;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the {@code obsidian://} deep link handed to a freshly onboarded collaborator after a
 * successful invite login - the plugin registers a handler for the {@code stonesync-connect}
 * action (see {@code onboarding/DeepLinkHandler.ts}) that reads these params and immediately
 * exchanges the code for the real API key via {@code POST /api/auth/exchange}
 * ({@link ApiKeyExchangeService}) - the actual long-lived key deliberately never appears in this
 * URL, since a deep link otherwise sits in plaintext in the browser's history.
 */
public final class DeepLinkBuilder {

    private DeepLinkBuilder() {
    }

    public static String build(String serverUrl, String exchangeCode) {
        return "obsidian://stonesync-connect?"
                + "serverUrl=" + encode(serverUrl)
                + "&exchangeCode=" + encode(exchangeCode);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
