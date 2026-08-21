package de.tstieh.stonesync.auth;

import de.tstieh.stonesync.invite.AuthentikLoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyHasher apiKeyHasher;

    public SecurityConfig(ApiKeyRepository apiKeyRepository, ApiKeyHasher apiKeyHasher) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyHasher = apiKeyHasher;
    }

    /**
     * Entirely separate auth mechanism for an entirely separate URL space: the browser-based
     * collaborator-invite login and the self-service dashboard (session cookies + Authentik
     * OAuth2 login), vs. the Bearer API-key auth every other endpoint below uses. Ordered before
     * the default chain so these paths never hit {@link ApiKeyAuthFilter} (which would otherwise
     * require a Bearer header that a plain browser visit never sends, well before OAuth2 login
     * even gets a chance to run).
     *
     * <p>{@code /dashboard/**} requires an authenticated session - visiting it unauthenticated is
     * exactly what triggers {@code oauth2Login}'s redirect into Authentik in the first place, so
     * that's also the URL an Authentik "launch" link should point at.</p>
     */
    @Bean
    @Order(1)
    @Profile(AuthentikProfileActivator.PROFILE)
    public SecurityFilterChain oauthLoginFilterChain(HttpSecurity http, AuthentikLoginSuccessHandler successHandler)
            throws Exception {
        http
                .securityMatcher("/login/**", "/oauth2/**", "/invite/**", "/api/auth/exchange", "/dashboard/**")
                // /api/auth/exchange is the one state-changing POST on this surface that must stay
                // reachable with no session/CSRF token yet - it hands out a device's very first
                // credential. The dashboard's invite-creation POST is a normal session-authenticated
                // form submission and DOES get CSRF-protected (cookie-based repository, since no
                // templating engine is configured to embed the token via a request attribute).
                //
                // Explicit plain CsrfTokenRequestAttributeHandler rather than Spring Security 6's
                // default (BREACH-protection XorCsrfTokenRequestAttributeHandler): that default
                // expects the submitted token to be XOR-masked relative to the actual one, which is
                // only handled automatically for you by Thymeleaf's th:action / a JS layer reading a
                // meta tag - DashboardController hand-writes the form and embeds csrfToken.getToken()
                // literally, so the request handler has to expect that same raw value back.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/auth/exchange"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/dashboard/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2Login(oauth2 -> oauth2.successHandler(successHandler));
        return http.build();
    }

    /**
     * Same URL space without Authentik configured (see {@link AuthentikProfileActivator}): the
     * OAuth2 login itself is unavailable, but {@code /api/auth/exchange} must keep working as an
     * unauthenticated endpoint - otherwise it would fall through to {@link ApiKeyAuthFilter} and
     * demand the very credential it exists to hand out.
     */
    @Bean
    @Order(1)
    @Profile("!" + AuthentikProfileActivator.PROFILE)
    public SecurityFilterChain inviteExchangeOnlyFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/login/**", "/oauth2/**", "/invite/**", "/api/auth/exchange")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/ws/**").permitAll() // authenticated via handshake ticket instead
                        // Container-internal forward target for thrown exceptions. Without this,
                        // every error response (even correctly @ResponseStatus-annotated ones
                        // like DocumentNotFoundException) gets masked as an opaque 403, because
                        // the SecurityContext isn't preserved across the /error dispatch.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyRepository, apiKeyHasher),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
