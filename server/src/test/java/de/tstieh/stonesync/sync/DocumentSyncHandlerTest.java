package de.tstieh.stonesync.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import de.tstieh.stonesync.access.AccessLevel;
import de.tstieh.stonesync.audit.AuditService;
import de.tstieh.stonesync.auth.WsHandshakeInterceptor;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentSyncHandlerTest {

    @Mock
    private UpdateLogService updateLogService;
    @Mock
    private SnapshotService snapshotService;
    @Mock
    private SyncSessionRegistry registry;
    @Mock
    private YjsSnapshotRepository snapshotRepository;
    @Mock
    private YjsUpdateRepository updateRepository;
    @Mock
    private DocumentRestoreQueueService restoreQueueService;
    @Mock
    private AuditService auditService;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private WebSocketSession sender;
    @Mock
    private WebSocketSession otherClient;

    private DocumentSyncHandler handler;
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        handler = new DocumentSyncHandler(updateLogService, snapshotService, registry,
                snapshotRepository, updateRepository, restoreQueueService, auditService, documentRepository);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put(WsHandshakeInterceptor.ACCESS_LEVEL_ATTRIBUTE, AccessLevel.EDITOR);
        attrs.put(WsHandshakeInterceptor.USER_ID_ATTRIBUTE, UUID.randomUUID());
        lenient().when(sender.getUri()).thenReturn(URI.create("/ws/sync/" + documentId));
        lenient().when(sender.getAttributes()).thenReturn(attrs);
        lenient().when(sender.getId()).thenReturn("sender-session");
        lenient().when(sender.isOpen()).thenReturn(true);
        lenient().when(otherClient.getId()).thenReturn("other-session");
        lenient().when(otherClient.isOpen()).thenReturn(true);
        lenient().when(snapshotRepository.findById(documentId)).thenReturn(Optional.empty());
        lenient().when(updateRepository.findByDocumentIdOrderByIdAsc(documentId)).thenReturn(List.of());
        lenient().when(restoreQueueService.consumePending(documentId)).thenReturn(Optional.empty());
    }

    private byte[] messageOf(int prefix, byte... payload) {
        byte[] full = new byte[payload.length + 1];
        full[0] = (byte) prefix;
        System.arraycopy(payload, 0, full, 1, payload.length);
        return full;
    }

    @Test
    @DisplayName("a read-only collaborator's document update is neither persisted nor relayed")
    void viewerCannotWriteThroughTheSyncSocket() throws Exception {
        sender.getAttributes().put(WsHandshakeInterceptor.ACCESS_LEVEL_ATTRIBUTE, AccessLevel.VIEWER);

        handler.handleMessage(sender, new BinaryMessage(messageOf(0x00, (byte) 1, (byte) 2)));

        verify(updateLogService, never()).append(any(), any());
        verify(otherClient, never()).sendMessage(any());
    }

    @Test
    @DisplayName("a read-only collaborator may still receive and send presence")
    void viewerMayStillShareCursorPresence() throws Exception {
        sender.getAttributes().put(WsHandshakeInterceptor.ACCESS_LEVEL_ATTRIBUTE, AccessLevel.VIEWER);
        when(registry.sessionsFor(documentId)).thenReturn(java.util.Set.of(sender, otherClient));

        handler.handleMessage(sender, new BinaryMessage(messageOf(0x01, (byte) 9)));

        verify(otherClient).sendMessage(any(BinaryMessage.class));
    }

    @Test
    @DisplayName("a snapshot payload from a read-only collaborator is refused too")
    void viewerCannotCompactTheLog() throws Exception {
        sender.getAttributes().put(WsHandshakeInterceptor.ACCESS_LEVEL_ATTRIBUTE, AccessLevel.VIEWER);

        handler.handleMessage(sender, new BinaryMessage(messageOf(0x03, (byte) 5)));

        verify(snapshotService, never()).replaceLogWithSnapshot(any(), any());
    }

    @Test
    @DisplayName("a newly connecting client is told about the presence of everyone already in the document")
    void newClientReceivesExistingAwarenessStates() throws Exception {
        // The already-connected peer announced itself while it was alone in the document.
        when(registry.sessionsFor(documentId)).thenReturn(java.util.Set.of(otherClient));
        when(otherClient.getUri()).thenReturn(URI.create("/ws/sync/" + documentId));
        handler.handleMessage(otherClient, new BinaryMessage(messageOf(0x01, (byte) 7, (byte) 8)));

        // Now a second client joins. Without a replay of the cached presence frames it would
        // never learn that anyone else is there - no remote cursor, until that peer happens to
        // move their cursor again.
        when(registry.sessionsFor(documentId)).thenReturn(java.util.Set.of(otherClient, sender));
        handler.afterConnectionEstablished(sender);

        ArgumentCaptor<BinaryMessage> captor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues().stream().map(m -> m.getPayload().array()))
                .anySatisfy(frame -> assertThat(frame).containsExactly(0x01, 7, 8));
    }

    @Test
    @DisplayName("presence of a disconnected client is not replayed to later joiners")
    void awarenessOfClosedSessionIsForgotten() throws Exception {
        when(registry.sessionsFor(documentId)).thenReturn(java.util.Set.of(otherClient));
        when(otherClient.getUri()).thenReturn(URI.create("/ws/sync/" + documentId));
        handler.handleMessage(otherClient, new BinaryMessage(messageOf(0x01, (byte) 7, (byte) 8)));
        handler.afterConnectionClosed(otherClient, org.springframework.web.socket.CloseStatus.NORMAL);

        lenient().when(registry.sessionsFor(documentId)).thenReturn(java.util.Set.of(sender));
        handler.afterConnectionEstablished(sender);

        ArgumentCaptor<BinaryMessage> captor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues().stream().map(m -> m.getPayload().array()))
                .noneSatisfy(frame -> assertThat(frame).containsExactly(0x01, 7, 8));
    }

    @Test
    @DisplayName("0x00 document update is persisted and broadcast to all other clients of the same document")
    void docUpdateIsPersistedAndBroadcastToOthers() throws Exception {
        doReturn(java.util.Set.of(sender, otherClient)).when(registry).sessionsFor(documentId);
        when(updateLogService.exceedsSnapshotThreshold(documentId)).thenReturn(false);

        byte[] message = messageOf(0x00, (byte) 1, (byte) 2, (byte) 3);
        handler.handleMessage(sender, new BinaryMessage(message));

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(updateLogService).append(eq(documentId), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsExactly(1, 2, 3);

        verify(otherClient).sendMessage(any(BinaryMessage.class));
        verify(sender, never()).sendMessage(any(BinaryMessage.class));
    }

    @Test
    @DisplayName("0x01 awareness update is NOT persisted, only routed on to other clients")
    void awarenessIsRoutedButNeverPersisted() throws Exception {
        doReturn(java.util.Set.of(sender, otherClient)).when(registry).sessionsFor(documentId);

        byte[] message = messageOf(0x01, (byte) 5, (byte) 6);
        handler.handleMessage(sender, new BinaryMessage(message));

        verify(updateLogService, never()).append(any(), any());
        verify(otherClient).sendMessage(any(BinaryMessage.class));
    }

    @Test
    @DisplayName("0x03 snapshot payload from the client replaces the update log via the SnapshotService")
    void snapshotPayloadReplacesLog() throws Exception {
        byte[] snapshotBytes = {7, 8, 9};
        handler.handleMessage(sender, new BinaryMessage(messageOf(0x03, snapshotBytes)));

        verify(snapshotService).replaceLogWithSnapshot(documentId, snapshotBytes);
    }

    @Test
    @DisplayName("when the threshold is exceeded after an update, an active client receives REQUEST_SNAPSHOT")
    void requestsSnapshotWhenThresholdExceeded() throws Exception {
        doReturn(java.util.Set.of(sender, otherClient)).when(registry).sessionsFor(documentId);
        when(updateLogService.exceedsSnapshotThreshold(documentId)).thenReturn(true);
        when(updateLogService.currentMaxId(documentId)).thenReturn(java.util.Optional.of(42L));

        handler.handleMessage(sender, new BinaryMessage(messageOf(0x00, (byte) 1)));

        ArgumentCaptor<BinaryMessage> senderCaptor = ArgumentCaptor.forClass(BinaryMessage.class);
        ArgumentCaptor<BinaryMessage> otherCaptor = ArgumentCaptor.forClass(BinaryMessage.class);
        org.mockito.Mockito.verify(sender, org.mockito.Mockito.atMost(2)).sendMessage(senderCaptor.capture());
        org.mockito.Mockito.verify(otherClient, org.mockito.Mockito.atMost(2)).sendMessage(otherCaptor.capture());

        boolean sawRequestSnapshot = java.util.stream.Stream.concat(
                        senderCaptor.getAllValues().stream(), otherCaptor.getAllValues().stream())
                .anyMatch(m -> m.getPayload().get(0) == 0x02);
        assertThat(sawRequestSnapshot).isTrue();
    }

    @Test
    @DisplayName("before sending REQUEST_SNAPSHOT, the current log watermark is marked (prevents data loss on concurrent updates)")
    void marksSnapshotWatermarkBeforeRequestingSnapshot() throws Exception {
        doReturn(java.util.Set.of(sender)).when(registry).sessionsFor(documentId);
        when(updateLogService.exceedsSnapshotThreshold(documentId)).thenReturn(true);
        when(updateLogService.currentMaxId(documentId)).thenReturn(java.util.Optional.of(99L));

        handler.handleMessage(sender, new BinaryMessage(messageOf(0x00, (byte) 1)));

        verify(snapshotService).markPendingSnapshot(documentId, 99L);
    }

    @Test
    @DisplayName("on connect, an existing snapshot and update log are replayed to the new session only, followed by CAUGHT_UP")
    void connectingSessionReceivesExistingSnapshotAndUpdatesFollowedByCaughtUp() throws Exception {
        when(snapshotRepository.findById(documentId))
                .thenReturn(Optional.of(new YjsSnapshotEntity(documentId, new byte[]{1, 1}, Instant.now())));
        when(updateRepository.findByDocumentIdOrderByIdAsc(documentId)).thenReturn(List.of(
                new YjsUpdateEntity(documentId, new byte[]{2, 2}, Instant.now()),
                new YjsUpdateEntity(documentId, new byte[]{3, 3}, Instant.now())));

        handler.afterConnectionEstablished(sender);

        ArgumentCaptor<BinaryMessage> captor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(sender, times(4)).sendMessage(captor.capture());
        List<BinaryMessage> sent = captor.getAllValues();

        assertThat(sent.get(0).getPayload().array()).containsExactly(0x00, 1, 1);
        assertThat(sent.get(1).getPayload().array()).containsExactly(0x00, 2, 2);
        assertThat(sent.get(2).getPayload().array()).containsExactly(0x00, 3, 3);
        assertThat(sent.get(3).getPayload().array()).containsExactly(0x04);

        verify(otherClient, never()).sendMessage(any(BinaryMessage.class));
    }

    @Test
    @DisplayName("on connect with no existing history, only CAUGHT_UP is sent (no empty snapshot/update frames)")
    void connectingSessionWithNoHistoryReceivesOnlyCaughtUp() throws Exception {
        handler.afterConnectionEstablished(sender);

        ArgumentCaptor<BinaryMessage> captor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(sender, times(1)).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload().array()).containsExactly(0x04);
    }

    @Test
    @DisplayName("connecting still registers the session in the registry, exactly as before")
    void connectingStillRegistersInRegistry() throws Exception {
        handler.afterConnectionEstablished(sender);

        verify(registry).register(documentId, sender);
    }

    @Test
    @DisplayName("a pending restore is delivered right after CAUGHT_UP on connect, and then consumed")
    void connectingSessionReceivesPendingRestoreAfterCaughtUp() throws Exception {
        when(restoreQueueService.consumePending(documentId)).thenReturn(Optional.of("restored content"));

        handler.afterConnectionEstablished(sender);

        ArgumentCaptor<BinaryMessage> captor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(sender, times(2)).sendMessage(captor.capture());
        List<BinaryMessage> sent = captor.getAllValues();
        assertThat(sent.get(0).getPayload().array()).containsExactly(0x04);
        byte[] restorePayload = sent.get(1).getPayload().array();
        assertThat(restorePayload[0]).isEqualTo((byte) 0x05);
        assertThat(new String(restorePayload, 1, restorePayload.length - 1, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("restored content");
    }

    @Test
    @DisplayName("broadcastOrQueueRestore sends RESTORE_CONTENT live to every open session when any is connected")
    void broadcastOrQueueRestoreDeliversLiveWhenConnected() throws Exception {
        doReturn(java.util.Set.of(sender, otherClient)).when(registry).sessionsFor(documentId);

        handler.broadcastOrQueueRestore(documentId, "new content");

        ArgumentCaptor<BinaryMessage> senderCaptor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(sender).sendMessage(senderCaptor.capture());
        assertThat(senderCaptor.getValue().getPayload().array()[0]).isEqualTo((byte) 0x05);
        verify(otherClient).sendMessage(any(BinaryMessage.class));
        verify(restoreQueueService, never()).enqueue(any(), any());
    }

    @Test
    @DisplayName("broadcastOrQueueRestore queues instead of sending when no session is currently connected")
    void broadcastOrQueueRestoreQueuesWhenNoneConnected() {
        doReturn(java.util.Set.of()).when(registry).sessionsFor(documentId);

        handler.broadcastOrQueueRestore(documentId, "new content");

        verify(restoreQueueService).enqueue(documentId, "new content");
    }
}
