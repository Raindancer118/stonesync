package de.tstieh.stonesync.vaultevents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tstieh.stonesync.logging.AppLog;
import de.tstieh.stonesync.sync.DocumentEntity;
import de.tstieh.stonesync.sync.VaultEventBroadcaster;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;

/**
 * The vault-events WebSocket endpoint ({@code /ws/vault/{vaultId}}): a lightweight, JSON-text
 * side-channel entirely separate from the per-document Yjs binary sync channel
 * ({@code DocumentSyncHandler}). One connection per vault (not per file) tells every connected
 * client about documents being created or deleted anywhere in that vault, so a client can react
 * (auto-download a new file, remove a deleted one) without needing a persistent Yjs session open
 * for every single file in the vault - the scaling problem a naive "sync everything live" design
 * would otherwise hit.
 */
@Component
public class VaultEventsHandler extends TextWebSocketHandler implements VaultEventBroadcaster {

    private final VaultEventsSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public VaultEventsHandler(VaultEventsSessionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID vaultId = vaultIdOf(session);
        registry.register(vaultId, session);
        AppLog.debug("Vault-events connection established for vault {} (session {})", vaultId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID vaultId = vaultIdOf(session);
        registry.unregister(vaultId, session);
        AppLog.debug("Vault-events connection closed for vault {} (session {}, status {})", vaultId, session.getId(), status);
    }

    @Override
    public void notifyDocumentCreated(UUID vaultId, UUID documentId, String path, DocumentEntity.ContentType contentType,
                                       String originSessionId) {
        broadcast(vaultId, new VaultEventMessage(VaultEventMessage.TYPE_DOCUMENT_CREATED,
                documentId.toString(), path, contentType.name(), originSessionId));
    }

    @Override
    public void notifyDocumentDeleted(UUID vaultId, UUID documentId, String path, String originSessionId) {
        broadcast(vaultId, new VaultEventMessage(VaultEventMessage.TYPE_DOCUMENT_DELETED,
                documentId.toString(), path, null, originSessionId));
    }

    private void broadcast(UUID vaultId, VaultEventMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            AppLog.error("Failed to serialize vault event {} for vault {}: {}", message.type(), vaultId, e.getMessage());
            return;
        }

        int notified = 0;
        for (WebSocketSession session : registry.sessionsFor(vaultId)) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(json));
                    notified++;
                } catch (IOException e) {
                    AppLog.warn("Failed to send vault event to session {}: {}", session.getId(), e.getMessage());
                }
            }
        }
        AppLog.debug("Broadcast {} for vault {} to {} session(s)", message.type(), vaultId, notified);
    }

    private UUID vaultIdOf(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        String[] segments = path.split("/");
        return UUID.fromString(segments[segments.length - 1]);
    }
}
