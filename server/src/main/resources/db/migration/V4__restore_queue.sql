-- Holds at most one pending restore per document for a client that's currently offline: the
-- next time it connects, DocumentSyncHandler delivers this right after the normal catch-up
-- replay burst, then deletes the row (single delivery).
CREATE TABLE document_restore_queue (
    document_id  UUID PRIMARY KEY REFERENCES documents(id),
    content      TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
