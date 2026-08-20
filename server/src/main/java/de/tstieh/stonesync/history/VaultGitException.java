package de.tstieh.stonesync.history;

/** Wraps any low-level JGit/IO failure while materializing or reading a vault's git history. */
public class VaultGitException extends RuntimeException {

    public VaultGitException(String message, Throwable cause) {
        super(message, cause);
    }
}
