package de.tstieh.stonesync.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the application the way a deployment *with* Authentik configured does.
 *
 * <p>Regression test for a crash loop in production: the `authentik` profile was switched on
 * from an {@code EnvironmentPostProcessor}, which runs after config data has been loaded - the
 * profile counted as active, but {@code application-authentik.yml} was never read, so
 * {@code oauthLoginFilterChain} asked for a {@link ClientRegistrationRepository} that did not
 * exist and the context failed to start. Only the Authentik-less path had been covered before,
 * which is exactly why it went unnoticed.</p>
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles({"test", AuthentikProfileActivator.PROFILE})
@TestPropertySource(properties = {
        "AUTHENTIK_CLIENT_ID=test-client-id",
        "AUTHENTIK_CLIENT_SECRET=test-client-secret",
})
class AuthentikLoginContextTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stonesync")
            .withUsername("stonesync")
            .withPassword("stonesync");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private ClientRegistrationRepository clientRegistrations;

    @Test
    @DisplayName("with the authentik profile active, the OAuth2 login chain and its client registration exist")
    void oauthLoginIsWiredUpWhenTheProfileIsActive() {
        ClientRegistration registration = clientRegistrations.findByRegistrationId(AuthentikProfileActivator.PROFILE);
        assertThat(registration).isNotNull();
        assertThat(registration.getClientId()).isEqualTo("test-client-id");
        assertThat(registration.getProviderDetails().getTokenUri()).isNotBlank();
        assertThat(context.containsBean("oauthLoginFilterChain")).isTrue();
        assertThat(context.containsBean("inviteExchangeOnlyFilterChain")).isFalse();
    }

    @Test
    @DisplayName("a blank client id counts as 'no Authentik configured'")
    void blankClientIdIsNotConfigured() {
        assertThat(AuthentikProfileActivator.isConfigured(null)).isFalse();
        assertThat(AuthentikProfileActivator.isConfigured("   ")).isFalse();
        assertThat(AuthentikProfileActivator.isConfigured("real-id")).isTrue();
    }
}
