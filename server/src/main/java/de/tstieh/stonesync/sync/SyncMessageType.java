package de.tstieh.stonesync.sync;

/** Prefix bytes of the sync WebSocket wire protocol. See {@link DocumentSyncHandler}. */
public final class SyncMessageType {

    public static final byte DOC_UPDATE = 0x00;
    public static final byte AWARENESS = 0x01;
    public static final byte REQUEST_SNAPSHOT = 0x02;
    public static final byte SNAPSHOT_PAYLOAD = 0x03;
    /** Server-&gt;client only: marks the end of the on-connect catch-up replay burst. */
    public static final byte CAUGHT_UP = 0x04;
    /**
     * Server-&gt;client only: replaces the entire document content with the given UTF-8 plaintext
     * payload (a git-restore point-in-time). The client applies it as a whole-content replace
     * inside one Y.Doc transaction, producing an ordinary CRDT update - no special-casing needed
     * anywhere else in the sync path.
     */
    public static final byte RESTORE_CONTENT = 0x05;
    /** Server-&gt;client only: the document was deleted, connected clients should remove it locally. */
    public static final byte DELETE_NOTICE = 0x06;

    private SyncMessageType() {
    }
}
