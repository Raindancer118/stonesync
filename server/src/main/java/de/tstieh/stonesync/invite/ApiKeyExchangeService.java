package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.auth.ApiKeyHasher;
import de.tstieh.stonesync.logging.AppLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A short-lived, single-use code that stands in for a freshly minted device API key inside the
 * {@code obsidian://stonesync-connect} deep link, so the long-lived key itself never has to
 * appear in a URL (found via agy architecture review: a URL sits in plaintext in the browser's
 * history, and other locally installed apps can in principle register the same {@code obsidian://}
 * scheme). The plugin exchanges the code for the real key via {@code POST /api/auth/exchange}
 * immediately after receiving the deep link.
 */
@Service
public class ApiKeyExchangeService {

    private static final Duration VALIDITY = Duration.ofMinutes(2);

    private final ApiKeyExchangeRepository repository;
    private final ApiKeyHasher hasher;
    private final Clock clock;

    public ApiKeyExchangeService(ApiKeyExchangeRepository repository, ApiKeyHasher hasher, Clock clock) {
        this.repository = repository;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** Stores the API key behind a fresh exchange code and returns the raw code. */
    @Transactional
    public String create(String apiKey, UUID vaultId, String displayName) {
        String rawCode = hasher.generateRawKey();
        Instant now = clock.instant();
        ApiKeyExchangeEntity entity = new ApiKeyExchangeEntity(UUID.randomUUID(), hasher.hash(rawCode), apiKey,
                vaultId, displayName, now, now.plus(VALIDITY));
        repository.save(entity);
        // Never log the raw code or the API key itself - only that one was minted.
        AppLog.info("Minted exchange code for vault {} ({}), valid until {}", vaultId, displayName, entity.getExpiresAt());
        return rawCode;
    }

    /**
     * Validates and consumes an exchange code, returning the API key it stood in for. Throws
     * {@link ExchangeNotFoundException} for an unknown code, or {@link ExchangeNoLongerValidException}
     * for a known code that has expired or was already redeemed.
     */
    @Transactional
    public ExchangedApiKey redeem(String rawCode) {
        ApiKeyExchangeEntity exchange = repository.findByCodeHash(hasher.hash(rawCode))
                .orElseThrow(() -> {
                    AppLog.warn("Exchange code redemption failed: unknown code");
                    return new ExchangeNotFoundException("Unknown exchange code");
                });

        Instant now = clock.instant();
        if (exchange.isConsumed()) {
            AppLog.warn("Exchange code redemption failed: already used (vault {})", exchange.getVaultId());
            throw new ExchangeNoLongerValidException("This exchange code has already been used");
        }
        if (exchange.isExpired(now)) {
            AppLog.warn("Exchange code redemption failed: expired at {} (vault {})", exchange.getExpiresAt(), exchange.getVaultId());
            throw new ExchangeNoLongerValidException("This exchange code has expired");
        }

        exchange.markConsumed(now);
        repository.save(exchange);
        AppLog.info("Exchange code redeemed for vault {} ({})", exchange.getVaultId(), exchange.getDisplayName());
        return new ExchangedApiKey(exchange.getApiKey(), exchange.getVaultId(), exchange.getDisplayName());
    }
}
