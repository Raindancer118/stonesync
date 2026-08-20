package de.tstieh.stonesync.auth;

import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.logging.AppLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Same one-time-ticket handshake as {@link WsHandshakeInterceptor}, but for the vault-events
 * channel ({@code /ws/vault/{vaultId}}): the path segment is already the vaultId itself, so no
 * document lookup is needed before the {@link VaultAccessService#requireAccess} check.
 */
@Component
public class VaultWsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String USER_ID_ATTRIBUTE = "userId";

    private final TicketService ticketService;
    private final VaultAccessService vaultAccessService;

    public VaultWsHandshakeInterceptor(TicketService ticketService, VaultAccessService vaultAccessService) {
        this.ticketService = ticketService;
        this.vaultAccessService = vaultAccessService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Optional<UUID> ticket = WsHandshakeSupport.extractTicket(request);
        if (ticket.isEmpty()) {
            AppLog.warn("Rejected vault-events WS handshake: no ticket in query string ({})", request.getURI());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Optional<UUID> userId = ticketService.validateAndConsume(ticket.get());
        if (userId.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        UUID vaultId = WsHandshakeSupport.extractLastPathSegmentAsUuid(request);
        if (vaultId == null) {
            AppLog.warn("Rejected vault-events WS handshake: no valid vaultId in path ({})", request.getURI());
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        try {
            vaultAccessService.requireAccess(userId.get(), vaultId);
        } catch (VaultAccessDeniedException e) {
            AppLog.warn("Rejected vault-events WS handshake: user {} has no access to vault {}", userId.get(), vaultId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(USER_ID_ATTRIBUTE, userId.get());
        AppLog.debug("Accepted vault-events WS handshake for user {} on vault {}", userId.get(), vaultId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
