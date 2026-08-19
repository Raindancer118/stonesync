package de.tstieh.stonesync.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Authenticates REST requests carrying {@code Authorization: Bearer <api-key>}. */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyHasher hasher;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepository, ApiKeyHasher hasher) {
        this.apiKeyRepository = apiKeyRepository;
        this.hasher = hasher;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String rawKey = header.substring("Bearer ".length()).trim();
            Optional<UUID> userId = resolveUser(rawKey);
            if (userId.isPresent()) {
                var authentication = new UsernamePasswordAuthenticationToken(
                        userId.get(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    private Optional<UUID> resolveUser(String rawKey) {
        String hash = hasher.hash(rawKey);
        return apiKeyRepository.findByKeyHash(hash)
                .filter(key -> !key.isRevoked())
                .map(ApiKeyEntity::getUserId);
    }
}
