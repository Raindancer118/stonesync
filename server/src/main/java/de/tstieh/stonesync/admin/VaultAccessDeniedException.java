package de.tstieh.stonesync.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when an authenticated user has no {@code user_vault_access} row for a vault. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class VaultAccessDeniedException extends RuntimeException {

    public VaultAccessDeniedException(String message) {
        super(message);
    }
}
