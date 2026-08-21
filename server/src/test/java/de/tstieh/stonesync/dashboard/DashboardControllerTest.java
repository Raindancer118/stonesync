package de.tstieh.stonesync.dashboard;

import de.tstieh.stonesync.admin.AdminService;
import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.admin.VaultEntity;
import de.tstieh.stonesync.admin.VaultRepository;
import de.tstieh.stonesync.admin.VaultRole;
import de.tstieh.stonesync.auth.AuthentikProfileActivator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard through the real HTTP + security stack: unauthenticated visits bounce into
 * Authentik, an authenticated login with no StoneSync account gets a clear message instead of a
 * vault list, and invite creation is refused for anyone who isn't the vault's OWNER - mirrors the
 * scoping {@code AccessControlIntegrationTest} already enforces for the regular API.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(AuthentikProfileActivator.PROFILE)
class DashboardControllerTest {

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
        registry.add("stonesync.public.url", () -> "https://stonesync.test");
        // No real Authentik is reachable from this test - oidcLogin() fabricates the
        // authentication directly, but the client registration still has to exist for Spring
        // Security's OAuth2 machinery (and the unauthenticated-redirect path) to wire up at all.
        registry.add("spring.security.oauth2.client.registration.authentik.client-id", () -> "test-client");
        registry.add("spring.security.oauth2.client.registration.authentik.client-secret", () -> "test-secret");
        registry.add("spring.security.oauth2.client.registration.authentik.authorization-grant-type",
                () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.authentik.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        registry.add("spring.security.oauth2.client.registration.authentik.scope", () -> "openid,email,profile");
        registry.add("spring.security.oauth2.client.provider.authentik.authorization-uri",
                () -> "https://authentik.test/application/o/authorize/");
        registry.add("spring.security.oauth2.client.provider.authentik.token-uri",
                () -> "https://authentik.test/application/o/token/");
        registry.add("spring.security.oauth2.client.provider.authentik.user-info-uri",
                () -> "https://authentik.test/application/o/userinfo/");
        registry.add("spring.security.oauth2.client.provider.authentik.jwk-set-uri",
                () -> "https://authentik.test/application/o/stonesync/jwks/");
        registry.add("spring.security.oauth2.client.provider.authentik.user-name-attribute", () -> "email");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VaultRepository vaultRepository;
    @Autowired
    private AdminService adminService;

    private UserEntity user(String email) {
        return userRepository.save(new UserEntity(UUID.randomUUID(), email, "hash", Instant.now()));
    }

    private VaultEntity vault(String name, UUID ownerId) {
        return vaultRepository.save(new VaultEntity(UUID.randomUUID(), name, ownerId, Instant.now()));
    }

    @Test
    @DisplayName("an unauthenticated visit is bounced into the Authentik login")
    void unauthenticatedVisitRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/oauth2/authorization/authentik")));
    }

    @Test
    @DisplayName("a logged-in Authentik user with no StoneSync account sees a clear message, not a 403 page")
    void loginWithoutStoneSyncAccountShowsNoAccountPage() throws Exception {
        mockMvc.perform(get("/dashboard").with(oidcLogin().userInfoToken(token -> token.claim("email", "stranger@example.com"))))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No StoneSync account")));
    }

    @Test
    @DisplayName("an owner sees their vault with a create-invite form; a mere viewer does not")
    void dashboardShowsInviteFormOnlyForOwnedVaults() throws Exception {
        UserEntity owner = user("owner-" + UUID.randomUUID() + "@example.com");
        VaultEntity ownedVault = vault("owner-vault", owner.getId());
        adminService.grantAccess(owner.getId(), ownedVault.getId(), VaultRole.OWNER);

        UserEntity someoneElse = user("someone-else-" + UUID.randomUUID() + "@example.com");
        VaultEntity otherVault = vault("someone-elses-vault", someoneElse.getId());
        adminService.grantAccess(owner.getId(), otherVault.getId(), VaultRole.VIEWER);

        mockMvc.perform(get("/dashboard").with(oidcLogin().userInfoToken(token -> token.claim("email", owner.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("owner-vault"),
                        org.hamcrest.Matchers.containsString("someone-elses-vault"),
                        org.hamcrest.Matchers.containsString("/dashboard/vaults/" + ownedVault.getId() + "/invites"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/dashboard/vaults/" + otherVault.getId() + "/invites"))));
    }

    @Test
    @DisplayName("only the vault's OWNER may create an invite through the dashboard - a viewer is refused")
    void onlyOwnerMayCreateInviteThroughDashboard() throws Exception {
        UserEntity viewer = user("viewer-" + UUID.randomUUID() + "@example.com");
        UserEntity someoneElse = user("owner3-" + UUID.randomUUID() + "@example.com");
        VaultEntity vault = vault("viewer-only-vault", someoneElse.getId());
        adminService.grantAccess(viewer.getId(), vault.getId(), VaultRole.VIEWER);

        mockMvc.perform(post("/dashboard/vaults/{vaultId}/invites", vault.getId())
                        .with(oidcLogin().userInfoToken(token -> token.claim("email", viewer.getEmail())))
                        .cookie(new Cookie("XSRF-TOKEN", "test-csrf-token"))
                        .param("_csrf", "test-csrf-token")
                        .param("inviteeEmail", "colleague@example.com")
                        .param("role", "VIEWER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the owner can create an invite through the dashboard and gets back a usable link")
    void ownerCanCreateInviteThroughDashboard() throws Exception {
        UserEntity owner = user("owner2-" + UUID.randomUUID() + "@example.com");
        VaultEntity vault = vault("invite-me-vault", owner.getId());
        adminService.grantAccess(owner.getId(), vault.getId(), VaultRole.OWNER);

        mockMvc.perform(post("/dashboard/vaults/{vaultId}/invites", vault.getId())
                        .with(oidcLogin().userInfoToken(token -> token.claim("email", owner.getEmail())))
                        .cookie(new Cookie("XSRF-TOKEN", "test-csrf-token"))
                        .param("_csrf", "test-csrf-token")
                        .param("inviteeEmail", "colleague@example.com")
                        .param("role", "EDITOR"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "https://stonesync.test/invite/")));
    }
}
