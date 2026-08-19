package de.tstieh.stonesync.sync;

import de.tstieh.stonesync.auth.WsHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DocumentSyncHandler documentSyncHandler;
    private final WsHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(DocumentSyncHandler documentSyncHandler, WsHandshakeInterceptor handshakeInterceptor) {
        this.documentSyncHandler = documentSyncHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ConcurrentSendWebSocketHandlerDecorator(documentSyncHandler), "/ws/sync/{documentId}")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
