package de.tstieh.stonesync.auth;

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
import static org.mockito.Mockito.mock;

class WsHandshakeInterceptorTest {

    private TicketService ticketService;
    private WsHandshakeInterceptor interceptor;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        ticketService = new TicketService(new TicketProperties(15), clock);
        interceptor = new WsHandshakeInterceptor(ticketService);
    }

    private ServerHttpRequest requestWithQuery(String query) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setQueryString(query);
        servletRequest.setRequestURI("/ws/sync/doc");
        return new ServletServerHttpRequest(servletRequest);
    }

    @Test
    @DisplayName("gueltiges Ticket erlaubt den Handshake und traegt die userId in die Session-Attribute ein")
    void validTicketAllowsHandshake() throws Exception {
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
    @DisplayName("fehlendes Ticket lehnt den Handshake ab")
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
    @DisplayName("unbekanntes/ungueltiges Ticket lehnt den Handshake ab")
    void invalidTicketRejectsHandshake() throws Exception {
        ServerHttpRequest request = requestWithQuery("ticket=" + UUID.randomUUID());
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();
        WebSocketHandler handler = mock(WebSocketHandler.class);

        boolean result = interceptor.beforeHandshake(request, response, handler, attributes);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("ein Ticket kann fuer den Handshake nur einmal verwendet werden")
    void ticketIsConsumedAfterHandshake() throws Exception {
        UUID ticket = ticketService.issueTicket(userId);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        WebSocketHandler handler = mock(WebSocketHandler.class);

        interceptor.beforeHandshake(requestWithQuery("ticket=" + ticket), response, handler, new HashMap<>());
        boolean secondAttempt = interceptor.beforeHandshake(
                requestWithQuery("ticket=" + ticket), response, handler, new HashMap<>());

        assertThat(secondAttempt).isFalse();
    }
}
