package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

class TerminalHandshakeInterceptorTest {
  @Test
  void allowsAnAdministratorSession() {
    SessionService sessions = mock(SessionService.class);
    TerminalHandshakeInterceptor interceptor = new TerminalHandshakeInterceptor(sessions);
    Map<String, Object> attributes = new HashMap<>();
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "report_session=valid");
    when(request.getHeaders()).thenReturn(headers);
    LocalDateTime now = LocalDateTime.of(2026, 9, 7, 12, 0);
    when(sessions.currentUserFromCookieHeader("report_session=valid")).thenReturn(
        new UserRepository.UserAccount(1L, "admin", "hash", "admin", true, 1, now, now, now));

    boolean accepted = interceptor.beforeHandshake(
        request,
        mock(ServerHttpResponse.class),
        mock(WebSocketHandler.class),
        attributes);

    assertTrue(accepted);
    assertEquals("admin", attributes.get("terminalUsername"));
  }

  @Test
  void rejectsMissingSessions() {
    SessionService sessions = mock(SessionService.class);
    TerminalHandshakeInterceptor interceptor = new TerminalHandshakeInterceptor(sessions);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getHeaders()).thenReturn(new HttpHeaders());

    assertFalse(interceptor.beforeHandshake(
        request, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));
  }
}
