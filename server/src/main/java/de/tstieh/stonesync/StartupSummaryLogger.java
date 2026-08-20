package de.tstieh.stonesync;

import de.tstieh.stonesync.attachments.StorageProperties;
import de.tstieh.stonesync.invite.PublicUrlProperties;
import de.tstieh.stonesync.logging.AppLog;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs one readable, boxed summary once the application is fully up - active profile(s), port,
 * public URL (if configured) and storage path - instead of leaving an operator to piece that
 * together from scattered Spring Boot startup lines.
 */
@Component
public class StartupSummaryLogger implements ApplicationListener<ApplicationReadyEvent> {

    private final Environment environment;
    private final StorageProperties storageProperties;
    private final PublicUrlProperties publicUrlProperties;

    public StartupSummaryLogger(Environment environment, StorageProperties storageProperties,
                                 PublicUrlProperties publicUrlProperties) {
        this.environment = environment;
        this.storageProperties = storageProperties;
        this.publicUrlProperties = publicUrlProperties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String profiles = environment.getActiveProfiles().length == 0
                ? "default"
                : String.join(", ", environment.getActiveProfiles());
        String port = environment.getProperty("server.port", "8080");
        String publicUrl = publicUrlProperties.url() == null || publicUrlProperties.url().isBlank()
                ? "(not configured)"
                : publicUrlProperties.url();

        AppLog.info("""

                ================================================================
                 StoneSync server is ready
                   profile:      {}
                   listening on: :{}
                   public URL:   {}
                   storage path: {}
                ================================================================
                """, profiles, port, publicUrl, storageProperties.path());
    }
}
