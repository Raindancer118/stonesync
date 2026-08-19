package de.tstieh.stonesync.sync;

/** Prefix bytes of the sync WebSocket wire protocol. See {@link DocumentSyncHandler}. */
public final class SyncMessageType {

    public static final byte DOC_UPDATE = 0x00;
    public static final byte AWARENESS = 0x01;
    public static final byte REQUEST_SNAPSHOT = 0x02;
    public static final byte SNAPSHOT_PAYLOAD = 0x03;

    private SyncMessageType() {
    }
}
