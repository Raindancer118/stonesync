package de.tstieh.stonesync.auth;

import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.admin.VaultAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class VaultWsHandshakeInterceptorTest {

    private TicketService ticketService;
    private VaultAccessService vaultAccessService;
    private VaultWsHandshakeInterceptor interceptor;
    private final UUID userId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        ticketService = new TicketService(new TicketProperties(15), clock);
        vaultAccessService = mock(VaultAccessService.class);
        interceptor = new VaultWsHandshakeInterceptor(ticketService, vaultAccessService);
    }

    private ServerHttpRequest requestWithQuery(String query) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setQueryString(query);
        servletRequest.setRequestURI("/ws/vault/" + vaultId);
        return new ServletServerHttpRequest(servletRequest);
    }

    @Test
    @DisplayName("a valid ticket + vault access allows the handshake and stores the userId in the session attributes")
    void validTicketAndVaultAccessAllowsHandshake() {
        UUID ticket = ticketService.issueTicket(userId);
        ServerHttpRequest request = requestWithQuery("ticket=" + ticket);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        WebSocketHandler handler = mock(WebSocketHandler.class);

        boolean result = interceptor.beforeHandshake(request, response, handler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).containsEntry("userId", userId);
    }

    @Test
    @DisplayName("a missing ticket rejects the handshake")
    void missingTicketRejectsHandshake() {
        ServerHttpRequest request = requestWithQuery("");
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        WebSocketHandler handler = mock(WebSocketHandler.class);

        boolean result = interceptor.beforeHandshake(request, response, handler, attributes);

        assertThat(result).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("an unknown/invalid ticket rejects the handshake")
    void invalidTicketRejectsHandshake() {
        ServerHttpRequest request = requestWithQuery("ticket=" + UUID.randomUUID());
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        WebSocketHandler handler = mock(WebSocketHandler.class);

        boolean result = interceptor.beforeHandshake(request, response, handler, attributes);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("a ticket can only be used once for the handshake")
    void ticketIsConsumedAfterHandshake() {
        UUID ticket = ticketService.issueTicket(userId);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        WebSocketHandler handler = mock(WebSocketHandler.class);

        interceptor.beforeHandshake(requestWithQuery("ticket=" + ticket), response, handler, new HashMap<>());
        boolean secondAttempt = interceptor.beforeHandshake(
                requestWithQuery("ticket=" + ticket), response, handler, new HashMap<>());

        assertThat(secondAttempt).isFalse();
    }

    @Test
    @DisplayName("a valid ticket, but no vault access for the user, rejects the handshake (IDOR protection)")
    void validTicketButNoVaultAccessRejectsHandshake() {
        UUID ticket = ticketService.issueTicket(userId);
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService).requireAccess(userId, vaultId);
        ServerHttpRequest request = requestWithQuery("ticket=" + ticket);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        WebSocketHandler handler = mock(WebSocketHandler.class);

        boolean result = interceptor.beforeHandshake(request, response, handler, attributes);

        assertThat(result).isFalse();
        assertThat(attributes).isEmpty();
    }
}
