package de.tstieh.stonesync.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stonesync.sync")
public record SyncProperties(long snapshotThresholdCount, long snapshotThresholdBytes) {
}
