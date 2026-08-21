package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthentikLoginControllerTest {

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
    private HttpSession session;

    private AuthentikLoginController controller;

    @BeforeEach
    void setUp() {
        // Not a field initializer - see AuthentikLoginSuccessHandlerTest for why that would
        // silently capture nulls instead of the @Mock fields above.
        controller = new AuthentikLoginController(adminService, exchangeService, publicUrlProperties);
    }

    @Test
    void connectNowMintsAFreshKeyAndExchangeCodeFromSessionState() throws IOException {
        UUID userId = UUID.randomUUID();
        UUID vaultId = UUID.randomUUID();
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(AuthentikLoginController.SESSION_KEY_READY_USER_ID)).thenReturn(userId);
        when(session.getAttribute(AuthentikLoginController.SESSION_KEY_READY_VAULT_ID)).thenReturn(vaultId);
        when(session.getAttribute(AuthentikLoginController.SESSION_KEY_READY_DISPLAY_NAME)).thenReturn("Colleague");

        when(adminService.createApiKey(eq(userId), any())).thenReturn("raw-api-key");
        when(exchangeService.create("raw-api-key", vaultId, "Colleague")).thenReturn("exchange-code");
        when(publicUrlProperties.requireUrl()).thenReturn("https://stonesync.test");

        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        controller.connectNow(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        assertThat(body.toString())
                .contains("Open in Obsidian")
                .contains("obsidian://stonesync-connect")
                .contains("exchangeCode=exchange-code");
    }

    @Test
    void connectNowWithNoPendingSessionShowsAClearError() throws IOException {
        when(request.getSession(false)).thenReturn(null);

        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        controller.connectNow(request, response);

        verify(response).setStatus(HttpServletResponse.SC_GONE);
        assertThat(body.toString()).contains("fresh invite link");
    }
}
