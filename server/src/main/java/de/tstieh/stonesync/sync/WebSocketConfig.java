package de.tstieh.stonesync.sync;

import de.tstieh.stonesync.auth.VaultWsHandshakeInterceptor;
import de.tstieh.stonesync.auth.WsHandshakeInterceptor;
import de.tstieh.stonesync.vaultevents.VaultEventsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DocumentSyncHandler documentSyncHandler;
    private final WsHandshakeInterceptor handshakeInterceptor;
    private final VaultEventsHandler vaultEventsHandler;
    private final VaultWsHandshakeInterceptor vaultHandshakeInterceptor;

    public WebSocketConfig(DocumentSyncHandler documentSyncHandler, WsHandshakeInterceptor handshakeInterceptor,
                            VaultEventsHandler vaultEventsHandler, VaultWsHandshakeInterceptor vaultHandshakeInterceptor) {
        this.documentSyncHandler = documentSyncHandler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.vaultEventsHandler = vaultEventsHandler;
        this.vaultHandshakeInterceptor = vaultHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ConcurrentSendWebSocketHandlerDecorator(documentSyncHandler), "/ws/sync/{documentId}")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");

        registry.addHandler(new ConcurrentSendWebSocketHandlerDecorator(vaultEventsHandler), "/ws/vault/{vaultId}")
                .addInterceptors(vaultHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
