package de.tstieh.stonesync.auth;

import de.tstieh.stonesync.admin.VaultAccessDeniedException;
import de.tstieh.stonesync.admin.VaultAccessService;
import de.tstieh.stonesync.logging.AppLog;
import de.tstieh.stonesync.sync.DocumentNotFoundException;
import de.tstieh.stonesync.sync.DocumentService;
import org.springframework.http.HttpStatus;
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
 *
 * <p>A valid ticket only proves who the caller is - it says nothing about whether they may
 * access the {@code documentId} in the handshake URL. This also resolves that document's
 * vault and enforces {@link VaultAccessService#requireAccess}, closing what would otherwise
 * be an IDOR: any authenticated user could sync any document by guessing its UUID.</p>
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String USER_ID_ATTRIBUTE = "userId";

    private final TicketService ticketService;
    private final DocumentService documentService;
    private final VaultAccessService vaultAccessService;

    public WsHandshakeInterceptor(TicketService ticketService, DocumentService documentService,
                                   VaultAccessService vaultAccessService) {
        this.ticketService = ticketService;
        this.documentService = documentService;
        this.vaultAccessService = vaultAccessService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Optional<UUID> ticket = extractTicket(request);
        if (ticket.isEmpty()) {
            AppLog.warn("Rejected WS handshake: no ticket in query string ({})", request.getURI());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Optional<UUID> userId = ticketService.validateAndConsume(ticket.get());
        if (userId.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        UUID documentId = extractDocumentId(request);
        if (documentId == null) {
            AppLog.warn("Rejected WS handshake: no valid documentId in path ({})", request.getURI());
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        try {
            UUID vaultId = documentService.vaultIdOf(documentId);
            vaultAccessService.requireAccess(userId.get(), vaultId);
        } catch (DocumentNotFoundException e) {
            AppLog.warn("Rejected WS handshake: unknown document {}", documentId);
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        } catch (VaultAccessDeniedException e) {
            AppLog.warn("Rejected WS handshake: user {} has no access to document {}", userId.get(), documentId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(USER_ID_ATTRIBUTE, userId.get());
        AppLog.debug("Accepted WS handshake for user {} on document {}", userId.get(), documentId);
        return true;
    }

    /** The last URI path segment is the documentId (route: {@code /ws/sync/{documentId}}). */
    private UUID extractDocumentId(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        String[] segments = path.split("/");
        if (segments.length == 0) {
            return null;
        }
        try {
            return UUID.fromString(segments[segments.length - 1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
