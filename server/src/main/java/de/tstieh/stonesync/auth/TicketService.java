package de.tstieh.stonesync.auth;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues short-lived, single-use WebSocket handshake tickets.
 *
 * <p>Obsidian cannot set custom headers on a WebSocket handshake, so the long-lived API key
 * is exchanged once (over a regular authenticated REST call) for a one-time ticket that is
 * safe to place in the WS query string - even if it ends up in a reverse-proxy access log,
 * it is worthless within seconds and after first use.</p>
 */
@Service
public class TicketService {

    private final TicketProperties properties;
    private final Clock clock;
    private final Map<UUID, PendingTicket> tickets = new ConcurrentHashMap<>();

    public TicketService(TicketProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Issues a new single-use ticket for the given user, valid for the configured TTL. */
    public UUID issueTicket(UUID userId) {
        UUID ticket = UUID.randomUUID();
        Instant expiresAt = clock.instant().plusSeconds(properties.ttlSeconds());
        tickets.put(ticket, new PendingTicket(userId, expiresAt));
        return ticket;
    }

    /**
     * Validates and immediately consumes a ticket. A ticket can only ever be redeemed once,
     * regardless of whether the redemption succeeds or fails validation.
     */
    public Optional<UUID> validateAndConsume(UUID ticket) {
        PendingTicket pending = tickets.remove(ticket);
        if (pending == null) {
            return Optional.empty();
        }
        if (pending.expiresAt().isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(pending.userId());
    }

    private record PendingTicket(UUID userId, Instant expiresAt) {
    }
}
