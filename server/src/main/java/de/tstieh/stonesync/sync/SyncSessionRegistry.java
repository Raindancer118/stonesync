package de.tstieh.stonesync.sync;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** Tracks which WebSocket sessions are currently subscribed to which document. */
@Component
public class SyncSessionRegistry {

    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByDocument = new ConcurrentHashMap<>();

    public void register(UUID documentId, WebSocketSession session) {
        sessionsByDocument.computeIfAbsent(documentId, id -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void unregister(UUID documentId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByDocument.get(documentId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByDocument.remove(documentId, sessions);
            }
        }
    }

    public Set<WebSocketSession> sessionsFor(UUID documentId) {
        return sessionsByDocument.getOrDefault(documentId, Set.of());
    }
}
