package de.tstieh.stonesync.auth;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates the one-time ticket passed as a {@code ?ticket=} query parameter during the
 * WebSocket handshake and consumes it immediately. Obsidian's WebSocket client cannot set
 * custom headers, so this is the only place authentication can happen for the sync channel.
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String USER_ID_ATTRIBUTE = "userId";

    private final TicketService ticketService;

    public WsHandshakeInterceptor(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Optional<UUID> ticket = extractTicket(request);
        if (ticket.isEmpty()) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        Optional<UUID> userId = ticketService.validateAndConsume(ticket.get());
        if (userId.isEmpty()) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(USER_ID_ATTRIBUTE, userId.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private Optional<UUID> extractTicket(ServerHttpRequest request) {
        List<String> values = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .get("ticket");
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(values.get(0)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
