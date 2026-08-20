package de.tstieh.stonesync.auth;

import de.tstieh.stonesync.invite.AuthentikLoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
     * collaborator-invite login (session cookies + Authentik OAuth2 login), vs. the Bearer
     * API-key auth every other endpoint below uses. Ordered before the default chain so these
     * paths never hit {@link ApiKeyAuthFilter} (which would otherwise require a Bearer header
     * that a plain browser visit never sends, well before OAuth2 login even gets a chance to
     * run).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain oauthLoginFilterChain(HttpSecurity http, AuthentikLoginSuccessHandler successHandler)
            throws Exception {
        http
                .securityMatcher("/login/**", "/oauth2/**", "/invite/**", "/api/auth/exchange")
                // /api/auth/exchange is the one state-changing POST on this surface: it hands out
                // a device's very first credential before any session/CSRF token could exist.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .oauth2Login(oauth2 -> oauth2.successHandler(successHandler));
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
