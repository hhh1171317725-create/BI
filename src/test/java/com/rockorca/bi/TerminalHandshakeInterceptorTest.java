package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

class TerminalHandshakeInterceptorTest {
  @Test
  void allowsConnectionWithoutLoginSession() {
    TerminalHandshakeInterceptor interceptor = new TerminalHandshakeInterceptor();
    Map<String, Object> attributes = new HashMap<>();

    boolean accepted = interceptor.beforeHandshake(
        mock(ServerHttpRequest.class),
        mock(ServerHttpResponse.class),
        mock(WebSocketHandler.class),
        attributes);

    assertTrue(accepted);
    assertTrue(attributes.containsKey("terminalUsername"));
  }
}
