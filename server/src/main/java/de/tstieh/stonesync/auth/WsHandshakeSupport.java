package de.tstieh.stonesync.auth;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared query-string/path-parsing helpers for WebSocket handshake interceptors (see
 * {@link WsHandshakeInterceptor} and {@code VaultWsHandshakeInterceptor}) - both need to pull
 * the one-time ticket out of the query string and a resource id out of the last path segment.
 */
public final class WsHandshakeSupport {

    private WsHandshakeSupport() {
    }

    public static Optional<UUID> extractTicket(ServerHttpRequest request) {
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

    /** The last URI path segment as a UUID (route: {@code /ws/.../{id}}). */
    public static UUID extractLastPathSegmentAsUuid(ServerHttpRequest request) {
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
}
