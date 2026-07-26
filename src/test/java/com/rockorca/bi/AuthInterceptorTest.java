package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class AuthInterceptorTest {
  private SessionService sessions;
  private AuthInterceptor interceptor;

  @BeforeEach
  void setUp() {
    sessions = mock(SessionService.class);
    interceptor = new AuthInterceptor(sessions, new ObjectMapper());
  }

  @Test
  void loginApisAreAllowedWithoutSession() throws Exception {
    for (String path : new String[] {"/api/login", "/api/logout"}) {
      assertTrue(interceptor.preHandle(
          new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), new Object()));
    }
  }

  @Test
  void unauthenticatedApiGetsJson401() throws Exception {
    when(sessions.authenticated(any())).thenReturn(false);
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertFalse(interceptor.preHandle(
        new MockHttpServletRequest("GET", "/api/current"), response, new Object()));
    assertEquals(401, response.getStatus());
    assertTrue(response.getContentType().startsWith("application/json"));
    assertTrue(response.getContentAsString().contains("登录已失效"));
  }

  @Test
  void authenticatedApiPasses() throws Exception {
    when(sessions.authenticated(any())).thenReturn(true);
    assertTrue(interceptor.preHandle(
        new MockHttpServletRequest("GET", "/api/current"),
        new MockHttpServletResponse(),
        new Object()));
  }
}
