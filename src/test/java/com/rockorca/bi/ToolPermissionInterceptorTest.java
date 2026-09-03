package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class ToolPermissionInterceptorTest {
  private SessionService sessions;
  private UserService users;
  private ToolPermissionInterceptor interceptor;
  private UserRepository.UserAccount operator;

  @BeforeEach
  void setUp() {
    sessions = mock(SessionService.class);
    users = mock(UserService.class);
    interceptor = new ToolPermissionInterceptor(sessions, users, new ObjectMapper());
    LocalDateTime now = LocalDateTime.of(2026, 8, 27, 12, 0);
    operator = new UserRepository.UserAccount(
        2L, "operator", "hash", "user", true, 1, now, now, now);
  }

  @Test
  void blocksUnauthorizedToolApi() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/terminal/settings");
    MockHttpServletResponse response = new MockHttpServletResponse();
    when(sessions.currentUser(request)).thenReturn(operator);
    when(users.canUseTool(operator, "terminal")).thenReturn(false);

    assertFalse(interceptor.preHandle(request, response, new Object()));
    assertEquals(403, response.getStatus());
  }

  @Test
  void blocksBidMonitorWithoutPermission() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bid-monitor/page");
    MockHttpServletResponse response = new MockHttpServletResponse();
    when(sessions.currentUser(request)).thenReturn(operator);
    when(users.canUseTool(operator, "bidMonitor")).thenReturn(false);
    assertFalse(interceptor.preHandle(request, response, new Object()));
    assertEquals(403, response.getStatus());
  }

  @Test
  void allowsAuthorizedToolApi() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/messages");
    MockHttpServletResponse response = new MockHttpServletResponse();
    when(sessions.currentUser(request)).thenReturn(operator);
    when(users.canUseTool(operator, "chat")).thenReturn(true);

    assertTrue(interceptor.preHandle(request, response, new Object()));
  }
}
