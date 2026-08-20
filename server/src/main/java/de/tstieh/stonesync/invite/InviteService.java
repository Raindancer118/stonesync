package de.tstieh.stonesync.invite;

import de.tstieh.stonesync.admin.VaultRole;
import de.tstieh.stonesync.auth.ApiKeyHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Single-use, expiring invite links that grant a specific role on a specific vault to whoever
 * authenticates via Authentik and redeems the link (see {@code AuthentikLoginController}). The
 * raw token is only ever returned once, at creation time - only its hash is ever persisted,
 * reusing {@link ApiKeyHasher}'s scheme (invite tokens are equally high-entropy random values,
 * not user-chosen passwords, so the same non-salted SHA-256 lookup hash is appropriate here).
 */
@Service
public class InviteService {

    private static final Duration DEFAULT_VALIDITY = Duration.ofDays(7);

    private final VaultInviteRepository repository;
    private final ApiKeyHasher hasher;
    private final Clock clock;

    public InviteService(VaultInviteRepository repository, ApiKeyHasher hasher, Clock clock) {
        this.repository = repository;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** Creates a new invite bound to a specific colleague's email and returns the raw token. */
    @Transactional
    public String createInvite(UUID vaultId, VaultRole role, String inviteeEmail, UUID createdBy) {
        String rawToken = hasher.generateRawKey();
        Instant now = clock.instant();
        VaultInviteEntity entity = new VaultInviteEntity(UUID.randomUUID(), vaultId, role,
                hasher.hash(rawToken), normalize(inviteeEmail), createdBy, now, now.plus(DEFAULT_VALIDITY));
        repository.save(entity);
        return rawToken;
    }

    /**
     * Validates and consumes an invite token. Throws {@link InviteNotFoundException} for an
     * unknown token, {@link InviteNoLongerValidException} for a known token that has expired or
     * was already redeemed, or {@link InviteEmailMismatchException} if the Authentik-verified
     * email doesn't match who the invite was created for - a leaked link is then useless to
     * anyone else, since the invite is NOT consumed on a mismatch and can still be redeemed by
     * the right person.
     */
    @Transactional
    public RedeemedInvite redeem(String rawToken, String verifiedEmail) {
        VaultInviteEntity invite = repository.findByTokenHash(hasher.hash(rawToken))
                .orElseThrow(() -> new InviteNotFoundException("Unknown invite token"));

        Instant now = clock.instant();
        if (invite.isConsumed()) {
            throw new InviteNoLongerValidException("This invite has already been used");
        }
        if (invite.isExpired(now)) {
            throw new InviteNoLongerValidException("This invite has expired");
        }
        if (!invite.getInviteeEmail().equals(normalize(verifiedEmail))) {
            throw new InviteEmailMismatchException(
                    "This invite was created for a different email address");
        }

        invite.markConsumed(now);
        repository.save(invite);
        return new RedeemedInvite(invite.getVaultId(), invite.getRole());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase();
    }
}
