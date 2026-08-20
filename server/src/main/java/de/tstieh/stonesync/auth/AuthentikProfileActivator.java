package de.tstieh.stonesync.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.util.StringUtils;

/**
 * Turns the optional Authentik login on exactly when it is actually configured.
 *
 * <p>Spring Boot validates every declared OAuth2 client registration during startup and aborts
 * with "Client id of registration 'authentik' must not be empty" if one is blank. Since
 * {@code docker-compose.yml} always passes {@code AUTHENTIK_CLIENT_ID} (empty by default), a
 * plain self-hosted install - exactly what the README quickstart describes - crash-looped on
 * boot. The registration therefore lives in {@code application-authentik.yml}, activated here
 * only when a client id is actually present.</p>
 *
 * <p>Deliberately an {@code additionalProfiles} call on {@link SpringApplication} rather than an
 * {@code EnvironmentPostProcessor}: profile-specific config files are resolved by
 * {@code ConfigDataEnvironmentPostProcessor}, which runs near the very start of environment
 * post-processing. A profile switched on later is "active" but its YAML is never read - the
 * OAuth2 beans then reference a client registration that does not exist, and the context fails
 * to start. Additional profiles are known before that step and are honored properly.</p>
 */
public final class AuthentikProfileActivator {

    public static final String PROFILE = "authentik";
    static final String CLIENT_ID_ENV = "AUTHENTIK_CLIENT_ID";

    private AuthentikProfileActivator() {
    }

    public static void applyTo(SpringApplication application) {
        if (isConfigured(System.getenv(CLIENT_ID_ENV))) {
            application.setAdditionalProfiles(PROFILE);
        }
    }

    /** Visible for testing: an unset *and* a blank client id both mean "no Authentik". */
    static boolean isConfigured(String clientId) {
        return StringUtils.hasText(clientId);
    }
}
