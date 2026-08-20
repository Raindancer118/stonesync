package de.tstieh.stonesync.attachments;

import java.util.UUID;

/** Thrown when a document exists but has no stored attachment bytes (or the document itself is unknown). */
public class AttachmentNotFoundException extends RuntimeException {

    public AttachmentNotFoundException(UUID documentId) {
        super("Attachment not found for document: " + documentId);
    }
}
