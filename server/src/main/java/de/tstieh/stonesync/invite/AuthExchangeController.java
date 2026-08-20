package de.tstieh.stonesync.invite;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The plugin's counterpart to the {@code obsidian://stonesync-connect} deep link: it carries only
 * a short-lived, single-use exchange code (see {@link ApiKeyExchangeService}), never the actual
 * API key, so nothing sensitive ever sits in the URL/browser history. This endpoint is
 * intentionally unauthenticated (it hands out the very first credential a new device gets) - see
 * {@code SecurityConfig}'s permit-all matcher for this path.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthExchangeController {

    private final ApiKeyExchangeService exchangeService;

    public AuthExchangeController(ApiKeyExchangeService exchangeService) {
        this.exchangeService = exchangeService;
    }

    @PostMapping("/exchange")
    public ExchangedApiKey exchange(@Valid @RequestBody ExchangeRequest request) {
        return exchangeService.redeem(request.code());
    }

    public record ExchangeRequest(@NotBlank String code) {
    }
}
