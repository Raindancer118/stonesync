package de.tstieh.stonesync.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a vault delete is attempted while it still has documents. Vaults are never
 * cascade-deleted with their content - a vault's documents may hold irreplaceable synced
 * notes/attachments, so removing it requires an explicit, separate cleanup of its documents
 * first rather than one command silently destroying them.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class VaultNotEmptyException extends RuntimeException {

    public VaultNotEmptyException(String message) {
        super(message);
    }
}
