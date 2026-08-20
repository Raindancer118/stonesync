package de.tstieh.stonesync.vaultevents;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.tstieh.stonesync.sync.DocumentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultEventsHandlerTest {

    @Mock
    private VaultEventsSessionRegistry registry;
    @Mock
    private WebSocketSession session;

    private VaultEventsHandler handler;
    private final UUID vaultId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new VaultEventsHandler(registry, new ObjectMapper());
        lenient().when(session.getUri()).thenReturn(URI.create("/ws/vault/" + vaultId));
        lenient().when(session.getId()).thenReturn("session-1");
        lenient().when(session.isOpen()).thenReturn(true);
    }

    @Test
    @DisplayName("connecting registers the session in the registry, keyed by vaultId")
    void connectingRegistersInRegistry() {
        handler.afterConnectionEstablished(session);

        verify(registry).register(vaultId, session);
    }

    @Test
    @DisplayName("disconnecting unregisters the session")
    void disconnectingUnregisters() {
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(registry).unregister(vaultId, session);
    }

    @Test
    @DisplayName("notifyDocumentCreated broadcasts a document_created JSON message to every connected session")
    void notifyDocumentCreatedBroadcasts() throws Exception {
        doReturn(Set.of(session)).when(registry).sessionsFor(vaultId);

        handler.notifyDocumentCreated(vaultId, documentId, "notes/a.md", DocumentEntity.ContentType.TEXT, "session-abc");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"type\":\"document_created\"");
        assertThat(captor.getValue().getPayload()).contains("\"documentId\":\"" + documentId + "\"");
        assertThat(captor.getValue().getPayload()).contains("\"path\":\"notes/a.md\"");
        assertThat(captor.getValue().getPayload()).contains("\"contentType\":\"TEXT\"");
        assertThat(captor.getValue().getPayload()).contains("\"originSessionId\":\"session-abc\"");
    }

    @Test
    @DisplayName("notifyDocumentDeleted broadcasts a document_deleted JSON message")
    void notifyDocumentDeletedBroadcasts() throws Exception {
        doReturn(Set.of(session)).when(registry).sessionsFor(vaultId);

        handler.notifyDocumentDeleted(vaultId, documentId, "notes/a.md", null);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"type\":\"document_deleted\"");
        assertThat(captor.getValue().getPayload()).contains("\"documentId\":\"" + documentId + "\"");
    }

    @Test
    @DisplayName("does not send to a closed session")
    void doesNotSendToClosedSession() throws Exception {
        when(session.isOpen()).thenReturn(false);
        doReturn(Set.of(session)).when(registry).sessionsFor(vaultId);

        handler.notifyDocumentDeleted(vaultId, documentId, "notes/a.md", null);

        verify(session, never()).sendMessage(org.mockito.ArgumentMatchers.any());
    }
}
