package de.tstieh.stonesync.auth;

import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.sync.DocumentNotFoundException;
import de.tstieh.stonesync.sync.DocumentService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WsHandshakeInterceptorTest {

    private TicketService ticketService;
    private DocumentService documentService;
    private VaultAccessService vaultAccessService;
    private WsHandshakeInterceptor interceptor;
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID vaultId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        ticketService = new TicketService(new TicketProperties(15), clock);
        documentService = mock(DocumentService.class);
        vaultAccessService = mock(VaultAccessService.class);
        interceptor = new WsHandshakeInterceptor(ticketService, documentService, vaultAccessService);
    }

    private ServerHttpRequest requestWithQuery(String query) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setQueryString(query);
        servletRequest.setRequestURI("/ws/sync/" + documentId);
        return new ServletServerHttpRequest(servletRequest);
    }

    @Test
    @DisplayName("a valid ticket + vault access allows the handshake and stores the userId in the session attributes")
    void validTicketAndVaultAccessAllowsHandshake() throws Exception {
        UUID ticket = ticketService.issueTicket(userId);
        when(documentService.vaultIdOf(documentId)).thenReturn(vaultId);
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
    void missingTicketRejectsHandshake() throws Exception {
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
    void invalidTicketRejectsHandshake() throws Exception {
        ServerHttpRequest request = requestWithQuery("ticket=" + UUID.randomUUID());
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        WebSocketHandler handler = mock(WebSocketHandler.class);

        boolean result = interceptor.beforeHandshake(request, response, handler, attributes);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("a ticket can only be used once for the handshake")
    void ticketIsConsumedAfterHandshake() throws Exception {
        UUID ticket = ticketService.issueTicket(userId);
        when(documentService.vaultIdOf(documentId)).thenReturn(vaultId);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        WebSocketHandler handler = mock(WebSocketHandler.class);

        interceptor.beforeHandshake(requestWithQuery("ticket=" + ticket), response, handler, new HashMap<>());
        boolean secondAttempt = interceptor.beforeHandshake(
                requestWithQuery("ticket=" + ticket), response, handler, new HashMap<>());

        assertThat(secondAttempt).isFalse();
    }

    @Test
    @DisplayName("a valid ticket, but no vault access for the user, rejects the handshake (IDOR protection on the sync channel)")
    void validTicketButNoVaultAccessRejectsHandshake() throws Exception {
        UUID ticket = ticketService.issueTicket(userId);
        when(documentService.vaultIdOf(documentId)).thenReturn(vaultId);
        doThrow(new VaultAccessDeniedException("denied")).when(vaultAccessService).requireAccess(userId, vaultId);
        ServerHttpRequest request = requestWithQuery("ticket=" + ticket);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        WebSocketHandler handler = mock(WebSocketHandler.class);

        boolean result = interceptor.beforeHandshake(request, response, handler, attributes);

        assertThat(result).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("a valid ticket, but an unknown documentId in the URL, rejects the handshake")
    void validTicketButUnknownDocumentRejectsHandshake() throws Exception {
        UUID ticket = ticketService.issueTicket(userId);
        when(documentService.vaultIdOf(documentId)).thenThrow(new DocumentNotFoundException(documentId));
        ServerHttpRequest request = requestWithQuery("ticket=" + ticket);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        WebSocketHandler handler = mock(WebSocketHandler.class);

        boolean result = interceptor.beforeHandshake(request, response, handler, attributes);

        assertThat(result).isFalse();
    }
}
