package com.rockorca.bi;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class TerminalWebSocketHandlerTest {
  @Test
  void preservesTerminalWhitespaceAndControlCharacters() throws Exception {
    TerminalSshService terminal = mock(TerminalSshService.class);
    TerminalWebSocketHandler handler =
        new TerminalWebSocketHandler(terminal, new ObjectMapper());
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("terminal-1");

    handler.handleTextMessage(
        session,
        new TextMessage("{\"type\":\"input\",\"data\":\" \\r\"}"));

    verify(terminal).input("terminal-1", " \r");
  }
}
