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
    @DisplayName("ticket creation and expiry")
    class Lifecycle {

        @Test
        @DisplayName("an issued ticket is immediately valid and returns the user id")
        void issuedTicketIsValidImmediately() {
            MutableClock clock = MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
            TicketService service = new TicketService(new TicketProperties(15), clock);

            UUID ticket = service.issueTicket(userId);
            Optional<UUID> result = service.validateAndConsume(ticket);

            assertThat(result).contains(userId);
        }

        @Test
        @DisplayName("a ticket expires after the configured TTL")
        void ticketExpiresAfterTtl() {
            MutableClock clock = MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
            TicketService service = new TicketService(new TicketProperties(15), clock);

            UUID ticket = service.issueTicket(userId);
            clock.advance(16);

            assertThat(service.validateAndConsume(ticket)).isEmpty();
        }

        @Test
        @DisplayName("a ticket is valid within the TTL right up until just before expiry")
        void ticketStillValidJustBeforeExpiry() {
            MutableClock clock = MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
            TicketService service = new TicketService(new TicketProperties(15), clock);

            UUID ticket = service.issueTicket(userId);
            clock.advance(14);

            assertThat(service.validateAndConsume(ticket)).contains(userId);
        }
    }

    @Nested
    @DisplayName("single use")
    class SingleUse {

        @Test
        @DisplayName("an already consumed ticket is invalid on the second attempt")
        void ticketCannotBeUsedTwice() {
            MutableClock clock = MutableClock.at(Instant.parse("2026-01-01T00:00:00Z"));
            TicketService service = new TicketService(new TicketProperties(15), clock);

            UUID ticket = service.issueTicket(userId);
            service.validateAndConsume(ticket);

            assertThat(service.validateAndConsume(ticket)).isEmpty();
        }
    }

    @Nested
    @DisplayName("unknown tickets")
    class Unknown {

        @Test
        @DisplayName("a ticket that was never issued is invalid")
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
