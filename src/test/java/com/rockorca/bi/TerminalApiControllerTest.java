package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TerminalApiControllerTest {
  @Test
  void rejectsNonAdministratorsBeforeReadingTerminalSettings() {
    TerminalSshService terminal = mock(TerminalSshService.class);
    SessionService sessions = mock(SessionService.class);
    UserService users = mock(UserService.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    LocalDateTime now = LocalDateTime.of(2026, 9, 7, 12, 0);
    UserRepository.UserAccount operator = new UserRepository.UserAccount(
        2L, "operator", "hash", "user", true, 1, now, now, now);
    when(sessions.currentUser(request)).thenReturn(operator);
    doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "仅管理员可以执行此操作"))
        .when(users).requireAdmin(operator);

    TerminalApiController controller = new TerminalApiController(terminal, sessions, users);

    assertThrows(ResponseStatusException.class, () -> controller.settings(request));
    verifyNoInteractions(terminal);
  }
}
