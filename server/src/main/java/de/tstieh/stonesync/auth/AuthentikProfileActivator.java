package de.tstieh.stonesync.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Turns the optional Authentik login on exactly when it is actually configured.
 *
 * <p>Spring Boot validates every declared OAuth2 client registration during startup and aborts
 * with "Client id of registration 'authentik' must not be empty" if one is blank. Since
 * {@code docker-compose.yml} always passes {@code AUTHENTIK_CLIENT_ID} (empty by default), a
 * plain self-hosted install - exactly what the README quickstart describes - crash-looped on
 * boot. The registration therefore lives in {@code application-authentik.yml}, and this
 * post-processor activates that profile only when a client id is present.</p>
 */
public class AuthentikProfileActivator implements EnvironmentPostProcessor {

    public static final String PROFILE = "authentik";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String clientId = environment.getProperty("AUTHENTIK_CLIENT_ID");
        if (StringUtils.hasText(clientId)) {
            environment.addActiveProfile(PROFILE);
        }
    }
}
