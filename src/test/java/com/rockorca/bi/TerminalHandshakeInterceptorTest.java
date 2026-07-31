package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

class TerminalHandshakeInterceptorTest {
  @Test
  void allowsActiveAdministrator() {
    SessionService sessions = mock(SessionService.class);
    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    when(sessions.currentUser(servletRequest)).thenReturn(user("admin", true));
    TerminalHandshakeInterceptor interceptor = new TerminalHandshakeInterceptor(sessions);
    Map<String, Object> attributes = new HashMap<>();

    boolean accepted = interceptor.beforeHandshake(
        new ServletServerHttpRequest(servletRequest),
        mock(ServerHttpResponse.class),
        mock(WebSocketHandler.class),
        attributes);

    assertTrue(accepted);
    assertTrue(attributes.containsKey("terminalUsername"));
  }

  @Test
  void rejectsNonAdministrator() {
    SessionService sessions = mock(SessionService.class);
    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    when(sessions.currentUser(servletRequest)).thenReturn(user("user", true));
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    TerminalHandshakeInterceptor interceptor = new TerminalHandshakeInterceptor(sessions);

    boolean accepted = interceptor.beforeHandshake(
        new ServletServerHttpRequest(servletRequest),
        response,
        mock(WebSocketHandler.class),
        new HashMap<>());

    assertFalse(accepted);
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  private static UserRepository.UserAccount user(String role, boolean active) {
    LocalDateTime now = LocalDateTime.now();
    return new UserRepository.UserAccount(
        1, "tester", "hash", role, active, 1, now, now, now);
  }
}
