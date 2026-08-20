package de.tstieh.stonesync.invite;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when a syntactically valid, known invite has already expired or been redeemed. */
@ResponseStatus(HttpStatus.GONE)
public class InviteNoLongerValidException extends RuntimeException {

    public InviteNoLongerValidException(String message) {
        super(message);
    }
}
