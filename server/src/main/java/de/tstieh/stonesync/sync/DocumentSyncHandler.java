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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
 *   <li>{@code 0x04} CAUGHT_UP - server -&gt; client only, see {@link #afterConnectionEstablished}.</li>
 *   <li>{@code 0x05} RESTORE_CONTENT - server -&gt; client only, see {@link #broadcastOrQueueRestore}.</li>
 *   <li>{@code 0x06} DELETE_NOTICE - server -&gt; client only, see {@link #broadcastDeleteNotice}.</li>
 * </ul>
 */
@Component
public class DocumentSyncHandler extends AbstractWebSocketHandler
        implements DocumentDeletionBroadcaster, RestoreBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(DocumentSyncHandler.class);

    private final UpdateLogService updateLogService;
    private final SnapshotService snapshotService;
    private final SyncSessionRegistry registry;
    private final YjsSnapshotRepository snapshotRepository;
    private final YjsUpdateRepository updateRepository;
    private final DocumentRestoreQueueService restoreQueueService;

    public DocumentSyncHandler(UpdateLogService updateLogService, SnapshotService snapshotService,
                                SyncSessionRegistry registry, YjsSnapshotRepository snapshotRepository,
                                YjsUpdateRepository updateRepository, DocumentRestoreQueueService restoreQueueService) {
        this.updateLogService = updateLogService;
        this.snapshotService = snapshotService;
        this.registry = registry;
        this.snapshotRepository = snapshotRepository;
        this.updateRepository = updateRepository;
        this.restoreQueueService = restoreQueueService;
    }

    /**
     * Registers the session, then replays the document's existing history to it alone (never
     * broadcast - every other already-connected session already has this state applied).
     * Without this, a freshly connecting client with an empty local Y.Doc would receive nothing
     * unless another device happened to be online and pushing live updates at that exact
     * moment - the "dumb relay" design never implemented a sync-step-1/2 handshake, so this is
     * the one place that catch-up has to happen. The client applies each 0x00 frame via the
     * same `Y.applyUpdate` path it already uses for live updates - no new merge logic needed on
     * either side, just a marker (CAUGHT_UP) so the client knows when the replay burst ends,
     * sent even when there is nothing to replay so the signal is always deterministic.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID documentId = documentIdOf(session);
        registry.register(documentId, session);

        Optional<YjsSnapshotEntity> snapshot = snapshotRepository.findById(documentId);
        if (snapshot.isPresent()) {
            sendSafely(session, prefixed(SyncMessageType.DOC_UPDATE, snapshot.get().getStateBytes()));
        }

        List<YjsUpdateEntity> updates = updateRepository.findByDocumentIdOrderByIdAsc(documentId);
        for (YjsUpdateEntity update : updates) {
            sendSafely(session, prefixed(SyncMessageType.DOC_UPDATE, update.getUpdateBytes()));
        }

        sendSafely(session, new byte[]{SyncMessageType.CAUGHT_UP});

        // Delivered right after the catch-up burst (not before, not interleaved with it) so a
        // reconnecting client always has the full prior history applied before the corrective
        // restore replaces it - order doesn't matter for Yjs CRDT correctness either way, but
        // this keeps the two "the document's whole life so far" and "then it was restored"
        // stories in the intuitive order.
        restoreQueueService.consumePending(documentId)
                .ifPresent(content -> sendSafely(session, prefixed(SyncMessageType.RESTORE_CONTENT,
                        content.getBytes(StandardCharsets.UTF_8))));
    }

    private static byte[] prefixed(byte prefix, byte[] payload) {
        byte[] frame = new byte[payload.length + 1];
        frame[0] = prefix;
        System.arraycopy(payload, 0, frame, 1, payload.length);
        return frame;
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

    /**
     * Called by {@link DocumentService} right after a document is tombstoned, so any device
     * with that file open elsewhere reacts immediately (removes the local file, tears down its
     * session) instead of only finding out on its next reconnect.
     */
    @Override
    public void broadcastDeleteNotice(UUID documentId) {
        for (WebSocketSession peer : registry.sessionsFor(documentId)) {
            if (peer.isOpen()) {
                sendSafely(peer, new byte[]{SyncMessageType.DELETE_NOTICE});
            }
        }
    }

    /**
     * Called by {@link de.tstieh.stonesync.history.RestoreService} for every file present in a
     * restore target commit. Delivered live to every currently-connected session for that
     * document if any are open (it's the caller's responsibility to have already resolved the
     * documentId), or queued for the next connect otherwise.
     */
    @Override
    public void broadcastOrQueueRestore(UUID documentId, String content) {
        Set<WebSocketSession> sessions = registry.sessionsFor(documentId);
        boolean anyOpen = sessions.stream().anyMatch(WebSocketSession::isOpen);
        if (!anyOpen) {
            restoreQueueService.enqueue(documentId, content);
            return;
        }
        byte[] frame = prefixed(SyncMessageType.RESTORE_CONTENT, content.getBytes(StandardCharsets.UTF_8));
        for (WebSocketSession peer : sessions) {
            if (peer.isOpen()) {
                sendSafely(peer, frame);
            }
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
                .ifPresent(target -> {
                    // Record the watermark BEFORE asking a client to snapshot, so any DOC_UPDATE
                    // appended concurrently while the client builds its reply is never deleted
                    // by the subsequent compaction (see SnapshotService).
                    updateLogService.currentMaxId(documentId).ifPresent(maxId -> snapshotService.markPendingSnapshot(documentId, maxId));
                    sendSafely(target, new byte[]{SyncMessageType.REQUEST_SNAPSHOT});
                });
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
