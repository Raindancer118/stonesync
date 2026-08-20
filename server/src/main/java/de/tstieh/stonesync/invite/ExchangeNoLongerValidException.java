package de.tstieh.stonesync.invite;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when a known exchange code has expired or was already redeemed. */
@ResponseStatus(HttpStatus.GONE)
public class ExchangeNoLongerValidException extends RuntimeException {

    public ExchangeNoLongerValidException(String message) {
        super(message);
    }
}
