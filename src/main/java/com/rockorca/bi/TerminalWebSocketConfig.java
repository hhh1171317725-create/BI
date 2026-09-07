package com.rockorca.bi;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "DHH_TERMINAL_ENABLED", havingValue = "true", matchIfMissing = false)
public class TerminalWebSocketConfig implements WebSocketConfigurer {
  private final TerminalWebSocketHandler handler;
  private final TerminalHandshakeInterceptor handshakeInterceptor;

  public TerminalWebSocketConfig(
      TerminalWebSocketHandler handler,
      TerminalHandshakeInterceptor handshakeInterceptor) {
    this.handler = handler;
    this.handshakeInterceptor = handshakeInterceptor;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, "/ws/terminal")
        .addInterceptors(handshakeInterceptor);
  }
}
