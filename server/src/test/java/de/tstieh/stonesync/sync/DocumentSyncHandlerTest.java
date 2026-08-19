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

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    private WebSocketSession sender;
    @Mock
    private WebSocketSession otherClient;

    private DocumentSyncHandler handler;
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        handler = new DocumentSyncHandler(updateLogService, snapshotService, registry);

        Map<String, Object> attrs = new HashMap<>();
        lenient().when(sender.getUri()).thenReturn(URI.create("/ws/sync/" + documentId));
        lenient().when(sender.getAttributes()).thenReturn(attrs);
        lenient().when(sender.getId()).thenReturn("sender-session");
        lenient().when(sender.isOpen()).thenReturn(true);
        lenient().when(otherClient.getId()).thenReturn("other-session");
        lenient().when(otherClient.isOpen()).thenReturn(true);
    }

    private byte[] messageOf(int prefix, byte... payload) {
        byte[] full = new byte[payload.length + 1];
        full[0] = (byte) prefix;
        System.arraycopy(payload, 0, full, 1, payload.length);
        return full;
    }

    @Test
    @DisplayName("0x00 Dokument-Update wird persistiert und an alle anderen Clients desselben Dokuments verteilt")
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
    @DisplayName("0x01 Awareness-Update wird NICHT persistiert, nur an andere Clients weitergeroutet")
    void awarenessIsRoutedButNeverPersisted() throws Exception {
        doReturn(java.util.Set.of(sender, otherClient)).when(registry).sessionsFor(documentId);

        byte[] message = messageOf(0x01, (byte) 5, (byte) 6);
        handler.handleMessage(sender, new BinaryMessage(message));

        verify(updateLogService, never()).append(any(), any());
        verify(otherClient).sendMessage(any(BinaryMessage.class));
    }

    @Test
    @DisplayName("0x03 Snapshot-Payload vom Client ersetzt das Update-Log ueber den SnapshotService")
    void snapshotPayloadReplacesLog() throws Exception {
        byte[] snapshotBytes = {7, 8, 9};
        handler.handleMessage(sender, new BinaryMessage(messageOf(0x03, snapshotBytes)));

        verify(snapshotService).replaceLogWithSnapshot(documentId, snapshotBytes);
    }

    @Test
    @DisplayName("wird der Schwellwert nach einem Update ueberschritten, erhaelt ein aktiver Client REQUEST_SNAPSHOT")
    void requestsSnapshotWhenThresholdExceeded() throws Exception {
        doReturn(java.util.Set.of(sender, otherClient)).when(registry).sessionsFor(documentId);
        when(updateLogService.exceedsSnapshotThreshold(documentId)).thenReturn(true);

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
}
