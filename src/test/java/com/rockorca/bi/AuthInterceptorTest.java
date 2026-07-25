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
  void publicRoutesAreAllowedWithoutSession() throws Exception {
    for (String path : new String[] {
        "/login", "/api/login", "/api/logout", "/assets/miku-pet.png"
    }) {
      assertTrue(interceptor.preHandle(
          new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), new Object()));
    }
  }

  @Test
  void unauthenticatedApiGetsJson401AndPageGetsLoginRedirect() throws Exception {
    when(sessions.authenticated(any())).thenReturn(false);
    MockHttpServletResponse apiResponse = new MockHttpServletResponse();
    MockHttpServletResponse pageResponse = new MockHttpServletResponse();

    assertFalse(interceptor.preHandle(
        new MockHttpServletRequest("GET", "/api/current"), apiResponse, new Object()));
    assertEquals(401, apiResponse.getStatus());
    assertTrue(apiResponse.getContentAsString().contains("登录已失效"));

    assertFalse(interceptor.preHandle(
        new MockHttpServletRequest("GET", "/jd"), pageResponse, new Object()));
    assertEquals(302, pageResponse.getStatus());
    assertEquals("/login", pageResponse.getRedirectedUrl());
  }

  @Test
  void authenticatedRequestPasses() throws Exception {
    when(sessions.authenticated(any())).thenReturn(true);
    assertTrue(interceptor.preHandle(
        new MockHttpServletRequest("GET", "/api/current"),
        new MockHttpServletResponse(),
        new Object()));
  }
}
