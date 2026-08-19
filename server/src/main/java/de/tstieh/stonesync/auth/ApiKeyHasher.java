package de.tstieh.stonesync.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Hashes API keys with SHA-256 for constant-lookup DB storage/comparison. API keys are
 * high-entropy random tokens (not user-chosen passwords), so a per-record salt (bcrypt)
 * is not required for security and would prevent indexed lookup by hash.
 */
@Component
public class ApiKeyHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Generates a new random, high-entropy raw API key (only ever shown once to the user). */
    public String generateRawKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
