package de.tstieh.stonesync.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stonesync.ticket")
public record TicketProperties(long ttlSeconds) {

    public TicketProperties {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be positive");
        }
    }
}
