package de.tstieh.stonesync.sync;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps every session in a {@link ConcurrentWebSocketSessionDecorator} before it ever reaches
 * {@link DocumentSyncHandler}. Plain {@code WebSocketSession} implementations (e.g. Tomcat's)
 * are not thread-safe for concurrent {@code sendMessage} calls, but this handler legitimately
 * writes to the same session from multiple threads: the connecting thread replaying history in
 * {@code afterConnectionEstablished} can race with another thread's {@code broadcastToOthers}
 * delivering a live update to that same, still-catching-up session. Without this decorator that
 * race throws {@code IllegalStateException} and kills the connection.
 */
public class ConcurrentSendWebSocketHandlerDecorator extends WebSocketHandlerDecorator {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 1024 * 1024;

    private final Map<String, WebSocketSession> wrapped = new ConcurrentHashMap<>();

    public ConcurrentSendWebSocketHandlerDecorator(WebSocketHandler delegate) {
        super(delegate);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        wrapped.put(session.getId(), decorated);
        super.afterConnectionEstablished(decorated);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        super.handleMessage(wrapped.getOrDefault(session.getId(), session), message);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        WebSocketSession decorated = wrapped.remove(session.getId());
        super.afterConnectionClosed(decorated != null ? decorated : session, closeStatus);
    }
}
