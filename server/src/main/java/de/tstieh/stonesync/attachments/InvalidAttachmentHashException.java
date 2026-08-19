package de.tstieh.stonesync.attachments;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when the client-supplied content hash does not match the actual SHA-256 of the
 * uploaded bytes. Rejecting this before the hash ever reaches the filesystem layer also closes
 * a path-traversal vector: a value that has been verified to equal the real SHA-256 hex digest
 * can never contain path separators or "..".
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidAttachmentHashException extends RuntimeException {

    public InvalidAttachmentHashException(String message) {
        super(message);
    }
}
