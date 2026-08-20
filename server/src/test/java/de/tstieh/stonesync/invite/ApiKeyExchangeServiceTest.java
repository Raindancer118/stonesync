package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.auth.ApiKeyHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyExchangeServiceTest {

    @Mock
    private ApiKeyExchangeRepository repository;

    private final ApiKeyHasher hasher = new ApiKeyHasher();
    private ApiKeyExchangeService service;
    private final UUID vaultId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private static final String API_KEY = "the-real-long-lived-api-key";
    private static final String DISPLAY_NAME = "Jane Doe";

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        service = new ApiKeyExchangeService(repository, hasher, clock);
    }

    @Test
    @DisplayName("creating an exchange stores only the hashed code and expires 2 minutes later")
    void createStoresHashedCodeNotRawValue() {
        String rawCode = service.create(API_KEY, vaultId, DISPLAY_NAME);

        ArgumentCaptor<ApiKeyExchangeEntity> captor = ArgumentCaptor.forClass(ApiKeyExchangeEntity.class);
        verify(repository).save(captor.capture());

        ApiKeyExchangeEntity saved = captor.getValue();
        assertThat(saved.getCodeHash()).isEqualTo(hasher.hash(rawCode));
        assertThat(saved.getCodeHash()).isNotEqualTo(rawCode);
        assertThat(saved.getApiKey()).isEqualTo(API_KEY);
        assertThat(saved.getVaultId()).isEqualTo(vaultId);
        assertThat(saved.getDisplayName()).isEqualTo(DISPLAY_NAME);
        assertThat(saved.getExpiresAt()).isEqualTo(now.plusSeconds(120));
    }

    @Test
    @DisplayName("redeeming an unknown code fails")
    void redeemUnknownCodeThrows() {
        when(repository.findByCodeHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeem("not-a-real-code"))
                .isInstanceOf(ExchangeNotFoundException.class);
    }

    @Test
    @DisplayName("redeeming an expired code fails, even if never consumed before")
    void redeemExpiredCodeThrows() {
        String rawCode = "some-code";
        ApiKeyExchangeEntity exchange = new ApiKeyExchangeEntity(UUID.randomUUID(), hasher.hash(rawCode), API_KEY,
                vaultId, DISPLAY_NAME, now.minusSeconds(300), now.minusSeconds(60));
        when(repository.findByCodeHash(hasher.hash(rawCode))).thenReturn(Optional.of(exchange));

        assertThatThrownBy(() -> service.redeem(rawCode))
                .isInstanceOf(ExchangeNoLongerValidException.class);
    }

    @Test
    @DisplayName("redeeming an already-consumed code fails (single-use enforcement)")
    void redeemAlreadyConsumedCodeThrows() {
        String rawCode = "some-code";
        ApiKeyExchangeEntity exchange = new ApiKeyExchangeEntity(UUID.randomUUID(), hasher.hash(rawCode), API_KEY,
                vaultId, DISPLAY_NAME, now.minusSeconds(60), now.plusSeconds(60));
        exchange.markConsumed(now.minusSeconds(10));
        when(repository.findByCodeHash(hasher.hash(rawCode))).thenReturn(Optional.of(exchange));

        assertThatThrownBy(() -> service.redeem(rawCode))
                .isInstanceOf(ExchangeNoLongerValidException.class);
    }

    @Test
    @DisplayName("redeeming a valid, unexpired, unconsumed code returns the API key and marks it consumed")
    void redeemValidCodeReturnsApiKeyAndMarksConsumed() {
        String rawCode = "some-code";
        ApiKeyExchangeEntity exchange = new ApiKeyExchangeEntity(UUID.randomUUID(), hasher.hash(rawCode), API_KEY,
                vaultId, DISPLAY_NAME, now.minusSeconds(60), now.plusSeconds(60));
        when(repository.findByCodeHash(hasher.hash(rawCode))).thenReturn(Optional.of(exchange));

        ExchangedApiKey result = service.redeem(rawCode);

        assertThat(result.apiKey()).isEqualTo(API_KEY);
        assertThat(result.vaultId()).isEqualTo(vaultId);
        assertThat(result.displayName()).isEqualTo(DISPLAY_NAME);
        assertThat(exchange.isConsumed()).isTrue();
        verify(repository).save(exchange);
    }
}
