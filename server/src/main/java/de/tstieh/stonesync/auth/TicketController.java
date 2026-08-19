package de.tstieh.stonesync.auth;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Exchanges a valid API key (already authenticated by {@link ApiKeyAuthFilter}) for a WS ticket. */
@RestController
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/api/auth/ticket")
    public TicketResponse issueTicket(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        UUID ticket = ticketService.issueTicket(userId);
        return new TicketResponse(ticket);
    }

    public record TicketResponse(UUID ticket) {
    }
}
