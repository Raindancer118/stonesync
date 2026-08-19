package de.tstieh.stonesync.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketServiceTest {

    private final UUID userId = UUID.randomUUID();

    @Nested
    @DisplayName("Ticket-Erzeugung und Ablauf")
    class Lifecycle {

        @Test
        @DisplayName("erzeugtes Ticket ist unmittelbar gueltig und liefert die User-Id")
        void issuedTicketIsValidImmediately() {
            MutableClock clock = MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
            TicketService service = new TicketService(new TicketProperties(15), clock);

            UUID ticket = service.issueTicket(userId);
            Optional<UUID> result = service.validateAndConsume(ticket);

            assertThat(result).contains(userId);
        }

        @Test
        @DisplayName("Ticket laeuft nach der konfigurierten TTL ab")
        void ticketExpiresAfterTtl() {
            MutableClock clock = MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
            TicketService service = new TicketService(new TicketProperties(15), clock);

            UUID ticket = service.issueTicket(userId);
            clock.advance(16);

            assertThat(service.validateAndConsume(ticket)).isEmpty();
        }

        @Test
        @DisplayName("Ticket ist innerhalb der TTL bis kurz vor Ablauf gueltig")
        void ticketStillValidJustBeforeExpiry() {
            MutableClock clock = MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
            TicketService service = new TicketService(new TicketProperties(15), clock);

            UUID ticket = service.issueTicket(userId);
            clock.advance(14);

            assertThat(service.validateAndConsume(ticket)).contains(userId);
        }
    }

    @Nested
    @DisplayName("Einmal-Verbrauch")
    class SingleUse {

        @Test
        @DisplayName("ein bereits verbrauchtes Ticket ist beim zweiten Versuch ungueltig")
        void ticketCannotBeUsedTwice() {
            MutableClock clock = MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
            TicketService service = new TicketService(new TicketProperties(15), clock);

            UUID ticket = service.issueTicket(userId);
            service.validateAndConsume(ticket);

            assertThat(service.validateAndConsume(ticket)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Unbekannte Tickets")
    class Unknown {

        @Test
        @DisplayName("ein nie ausgestelltes Ticket ist ungueltig")
        void unknownTicketIsInvalid() {
            TicketService service = new TicketService(new TicketProperties(15),
                    Clock.fixed(Instant.now(), ZoneOffset.UTC));

            assertThat(service.validateAndConsume(UUID.randomUUID())).isEmpty();
        }
    }

    /** Simple mutable clock for deterministic TTL tests. */
    static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        static MutableClock at(Instant instant) {
            return new MutableClock(instant);
        }

        void advance(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
