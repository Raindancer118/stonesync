package de.tstieh.stonesync.history;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when a restore commit-ish doesn't resolve to any commit in the vault's git history. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class CommitNotFoundException extends RuntimeException {

    public CommitNotFoundException(String commitIsh) {
        super("Unknown commit: " + commitIsh);
    }
}
