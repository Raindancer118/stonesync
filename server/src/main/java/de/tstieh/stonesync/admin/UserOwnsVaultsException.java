package de.tstieh.stonesync.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a user delete is attempted while they still own vaults. Deleting them anyway
 * would either orphan the vault (owner_id pointing at a nonexistent user) or require a
 * cascade the caller didn't ask for - ownership must be transferred or the vault deleted
 * first instead.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class UserOwnsVaultsException extends RuntimeException {

    public UserOwnsVaultsException(String message) {
        super(message);
    }
}
