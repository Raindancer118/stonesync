package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.AdminService;
import de.tstieh.stonesync.admin.UserEntity;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.admin.VaultRole;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthentikLoginSuccessHandlerTest {

    @Mock
    private InviteService inviteService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminService adminService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private Authentication authentication;
    @Mock
    private HttpSession session;

    private AuthentikLoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        // Constructed here, not as a field initializer: field initializers run during instance
        // construction, before MockitoExtension has injected the @Mock fields above - a
        // constructor built there would silently capture nulls instead of the real mocks.
        handler = new AuthentikLoginSuccessHandler(inviteService, userRepository, adminService, new ApiKeyHasher());
    }

    @Test
    void redirectsToDashboardWhenNoInviteTokenIsPending() throws IOException {
        // No prior /invite/{token} visit means no HttpSession has ever been created for this
        // browser - request.getSession(false) returns null, exactly like a fresh visit to
        // /dashboard that Spring Security bounced into the Authentik login.
        when(request.getSession(false)).thenReturn(null);
        when(request.getContextPath()).thenReturn("");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("/dashboard");
    }

    @Test
    void redirectsToDashboardWhenSessionHasNoPendingInviteAttribute() throws IOException {
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(AuthentikLoginController.SESSION_KEY_PENDING_INVITE_TOKEN)).thenReturn(null);
        when(request.getContextPath()).thenReturn("");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("/dashboard");
    }

    @Test
    void grantsAccessAndDefersKeyMintingToConnectNow() throws IOException {
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(AuthentikLoginController.SESSION_KEY_PENDING_INVITE_TOKEN)).thenReturn("raw-token");

        OidcUser oidcUser = mock(OidcUser.class);
        when(authentication.getPrincipal()).thenReturn(oidcUser);
        when(oidcUser.getEmail()).thenReturn("colleague@example.com");
        when(oidcUser.getFullName()).thenReturn("Colleague Name");

        UUID vaultId = UUID.randomUUID();
        when(inviteService.redeem("raw-token", "colleague@example.com"))
                .thenReturn(new RedeemedInvite(vaultId, VaultRole.EDITOR));
        UserEntity user = new UserEntity(UUID.randomUUID(), "colleague@example.com", "hash", Instant.now());
        when(userRepository.findByEmail("colleague@example.com")).thenReturn(Optional.of(user));

        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(adminService).grantAccess(user.getId(), vaultId, VaultRole.EDITOR);
        // The device API key/exchange code (and thus the 2-minute redeem window) is only minted
        // once the person actually clicks "Connect now" - not here, right after login - see
        // AuthentikLoginController#connectNow.
        verify(adminService, never()).createApiKey(any(), any());
        verify(session).setAttribute(AuthentikLoginController.SESSION_KEY_READY_USER_ID, user.getId());
        verify(session).setAttribute(AuthentikLoginController.SESSION_KEY_READY_VAULT_ID, vaultId);
        verify(session).setAttribute(AuthentikLoginController.SESSION_KEY_READY_DISPLAY_NAME, "Colleague Name");
        assertThat(body.toString()).contains("Connect now").contains("BRAT").contains("/invite/connect");
    }
}
