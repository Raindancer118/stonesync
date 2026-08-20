package de.tstieh.stonesync.invite;

import java.util.UUID;

public record ExchangedApiKey(String apiKey, UUID vaultId, String displayName) {
}
