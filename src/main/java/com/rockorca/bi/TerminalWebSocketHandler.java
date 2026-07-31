package com.rockorca.bi;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {
  private final TerminalSshService terminal;
  private final ObjectMapper objectMapper;

  public TerminalWebSocketHandler(TerminalSshService terminal, ObjectMapper objectMapper) {
    this.terminal = terminal;
    this.objectMapper = objectMapper;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    terminal.open(session);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    if (message.getPayloadLength() > 65_536) return;
    Map<String, Object> payload =
        objectMapper.readValue(message.getPayload(), new TypeReference<>() {});
    String type = ReportService.text(payload.get("type"));
    if (type.equals("input")) {
      Object data = payload.get("data");
      terminal.input(session.getId(), data instanceof String text ? text : "");
    } else if (type.equals("resize")) {
      terminal.resize(
          session.getId(),
          integer(payload.get("columns"), 100),
          integer(payload.get("rows"), 30));
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    terminal.close(session.getId());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    terminal.close(session.getId());
  }

  private static int integer(Object value, int fallback) {
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
