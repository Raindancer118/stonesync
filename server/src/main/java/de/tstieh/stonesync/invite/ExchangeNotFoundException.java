package de.tstieh.stonesync.invite;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when an exchange code doesn't match any pending exchange. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ExchangeNotFoundException extends RuntimeException {

    public ExchangeNotFoundException(String message) {
        super(message);
    }
}
