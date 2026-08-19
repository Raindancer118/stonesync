package de.tstieh.stonesync.attachments;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stonesync.storage")
public record StorageProperties(String path) {
}
