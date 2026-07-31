package com.rockorca.bi;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class TerminalHandshakeInterceptor implements HandshakeInterceptor {
  private final SessionService sessions;

  public TerminalHandshakeInterceptor(SessionService sessions) {
    this.sessions = sessions;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attributes) {
    if (!(request instanceof ServletServerHttpRequest servletRequest)) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    UserRepository.UserAccount user =
        sessions.currentUser(servletRequest.getServletRequest());
    if (user == null || !user.active() || !user.admin()) {
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    attributes.put("terminalUsername", user.username());
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception exception) {
    // No handshake resources to release.
  }
}
