package de.tstieh.stonesync.admin;

import de.tstieh.stonesync.logging.AppLog;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Runs {@link BootstrapService} once at application startup and prints the API key, if minted. */
@Component
public class BootstrapRunner implements CommandLineRunner {

    private final BootstrapService bootstrapService;

    public BootstrapRunner(BootstrapService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    @Override
    public void run(String... args) {
        bootstrapService.runIfNeeded().ifPresent(result -> AppLog.warn("""

                ================================================================
                StoneSync initial admin bootstrap complete.
                  userId:  {}
                  vaultId: {}
                  API key: {}
                Save this API key now - it is stored only as a hash and cannot
                be retrieved again. Use it as the Bearer token in the plugin's
                server settings to obtain sync tickets.
                ================================================================
                """, result.userId(), result.vaultId(), result.rawApiKey()));
    }
}
