package de.tstieh.stonesync.invite;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class InviteNotFoundException extends RuntimeException {

    public InviteNotFoundException(String message) {
        super(message);
    }
}
