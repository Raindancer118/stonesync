package de.tstieh.stonesync.sync;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID documentId) {
        super("Document not found: " + documentId);
    }
}
