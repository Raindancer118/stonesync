package de.tstieh.stonesync.vaultevents;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** Tracks which WebSocket sessions are currently subscribed to which vault's events channel. */
@Component
public class VaultEventsSessionRegistry {

    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByVault = new ConcurrentHashMap<>();

    public void register(UUID vaultId, WebSocketSession session) {
        sessionsByVault.computeIfAbsent(vaultId, id -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void unregister(UUID vaultId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByVault.get(vaultId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByVault.remove(vaultId, sessions);
            }
        }
    }

    public Set<WebSocketSession> sessionsFor(UUID vaultId) {
        return sessionsByVault.getOrDefault(vaultId, Set.of());
    }
}
