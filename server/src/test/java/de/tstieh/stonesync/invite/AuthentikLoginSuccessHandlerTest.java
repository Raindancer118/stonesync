package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.AdminService;
import de.tstieh.stonesync.admin.UserRepository;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.io.IOException;

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
    private ApiKeyExchangeService exchangeService;
    @Mock
    private PublicUrlProperties publicUrlProperties;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private Authentication authentication;
    @Mock
    private HttpSession session;

    private final AuthentikLoginSuccessHandler handler = new AuthentikLoginSuccessHandler(
            inviteService, userRepository, adminService, exchangeService, new ApiKeyHasher(), publicUrlProperties);

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
}
