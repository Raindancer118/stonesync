package de.tstieh.stonesync.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configures the one-time initial-admin bootstrap (see {@link BootstrapService}). Solves the
 * chicken-and-egg problem that every {@code /api/admin/**} endpoint requires an API key, but
 * the very first API key can only be created through one of those endpoints.
 *
 * <p>{@code adminEmail} blank (the default) disables bootstrap entirely - an operator must set
 * {@code BOOTSTRAP_ADMIN_EMAIL} explicitly to opt in.</p>
 */
@ConfigurationProperties(prefix = "stonesync.bootstrap")
public record BootstrapProperties(String adminEmail, String vaultName, String deviceName) {

    public boolean isEnabled() {
        return adminEmail != null && !adminEmail.isBlank();
    }
}
