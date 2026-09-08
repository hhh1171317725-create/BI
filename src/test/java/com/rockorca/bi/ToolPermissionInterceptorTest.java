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
  @Test
  void membersCanOnlyReadSharedBidDataEvenWithToolPermission() throws Exception {
    when(users.canUseTool(operator,"bidMonitor")).thenReturn(true);
    for(String path:java.util.List.of("/api/bid-monitor/server-sync/pricing","/api/bid-monitor/server-sync/start",
        "/api/bid-monitor/server-sync/run","/api/bid-monitor/server-sync/forget","/api/bid-monitor/server-sync/page",
        "/api/bid-monitor/dingtalk/send","/api/bid-monitor/snapshot","/api/bid-monitor/import","/api/bid-monitor/page")){
      var request=new MockHttpServletRequest("POST",path);var response=new MockHttpServletResponse();
      when(sessions.currentUser(request)).thenReturn(operator);
      assertFalse(interceptor.preHandle(request,response,new Object()));assertEquals(403,response.getStatus());
    }
    for(String path:java.util.List.of("/api/bid-monitor/shared-report","/api/bid-monitor/gap")){
      var request=new MockHttpServletRequest("GET",path);when(sessions.currentUser(request)).thenReturn(operator);
      assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
    }
    var request=new MockHttpServletRequest("GET","/api/bid-monitor/dingtalk");when(sessions.currentUser(request)).thenReturn(operator);
    assertFalse(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
  }
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
