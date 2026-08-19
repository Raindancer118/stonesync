package de.tstieh.stonesync.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * The sync WebSocket endpoint: a "dumb" binary relay + persistence layer for Yjs updates.
 *
 * <p>Prefix-byte wire protocol (see {@link SyncMessageType}):</p>
 * <ul>
 *   <li>{@code 0x00} document update - appended to the update log and broadcast to every
 *       other client connected to the same document.</li>
 *   <li>{@code 0x01} awareness update (cursor/presence) - relayed live, never persisted.</li>
 *   <li>{@code 0x02} REQUEST_SNAPSHOT - server -&gt; client only.</li>
 *   <li>{@code 0x03} snapshot payload - client's answer to a REQUEST_SNAPSHOT, compacts the log.</li>
 * </ul>
 */
@Component
public class DocumentSyncHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentSyncHandler.class);

    private final UpdateLogService updateLogService;
    private final SnapshotService snapshotService;
    private final SyncSessionRegistry registry;

    public DocumentSyncHandler(UpdateLogService updateLogService, SnapshotService snapshotService,
                                SyncSessionRegistry registry) {
        this.updateLogService = updateLogService;
        this.snapshotService = snapshotService;
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.register(documentIdOf(session), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(documentIdOf(session), session);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        UUID documentId = documentIdOf(session);
        ByteBuffer buffer = message.getPayload();
        if (buffer.remaining() == 0) {
            return;
        }
        byte[] raw = new byte[buffer.remaining()];
        buffer.get(raw);
        byte prefix = raw[0];
        byte[] payload = Arrays.copyOfRange(raw, 1, raw.length);

        if (prefix == SyncMessageType.DOC_UPDATE) {
            updateLogService.append(documentId, payload);
            broadcastToOthers(documentId, session, raw);
            if (updateLogService.exceedsSnapshotThreshold(documentId)) {
                requestSnapshotFromAnyClient(documentId);
            }
        } else if (prefix == SyncMessageType.AWARENESS) {
            broadcastToOthers(documentId, session, raw);
        } else if (prefix == SyncMessageType.SNAPSHOT_PAYLOAD) {
            snapshotService.replaceLogWithSnapshot(documentId, payload);
        } else {
            log.warn("Unknown sync message prefix {} for document {}", prefix, documentId);
        }
    }

    private void broadcastToOthers(UUID documentId, WebSocketSession sender, byte[] raw) {
        for (WebSocketSession peer : registry.sessionsFor(documentId)) {
            if (!peer.getId().equals(sender.getId()) && peer.isOpen()) {
                sendSafely(peer, raw);
            }
        }
    }

    private void requestSnapshotFromAnyClient(UUID documentId) {
        Set<WebSocketSession> sessions = registry.sessionsFor(documentId);
        sessions.stream()
                .filter(WebSocketSession::isOpen)
                .findFirst()
                .ifPresent(target -> sendSafely(target, new byte[]{SyncMessageType.REQUEST_SNAPSHOT}));
    }

    private void sendSafely(WebSocketSession session, byte[] raw) {
        try {
            session.sendMessage(new BinaryMessage(raw));
        } catch (IOException e) {
            log.warn("Failed to send sync message to session {}: {}", session.getId(), e.getMessage());
        }
    }

    private UUID documentIdOf(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        String[] segments = path.split("/");
        String last = segments[segments.length - 1];
        return UUID.fromString(last);
    }
}
