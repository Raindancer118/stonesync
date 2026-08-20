package de.tstieh.stonesync.invite;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a known, still-valid invite is redeemed by an Authentik-verified email that
 * doesn't match the email the invite was created for. Kept distinct from
 * {@link InviteNoLongerValidException} because the invite itself is still valid - it just needs
 * to be redeemed by the right person - so it is deliberately NOT consumed when this is thrown.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class InviteEmailMismatchException extends RuntimeException {

    public InviteEmailMismatchException(String message) {
        super(message);
    }
}
