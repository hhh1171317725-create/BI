package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class SessionServiceTest {
  private SessionService sessions;

  @BeforeEach
  void setUp() {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.get("REPORT_USERNAME", "hhh")).thenReturn("hhh");
    when(config.get("REPORT_PASSWORD", "123456")).thenReturn("123456");
    when(config.get("REPORT_SESSION_SECRET", "")).thenReturn("test-secret-at-least-32-bytes");
    sessions = new SessionService(config, new ObjectMapper());
  }

  @Test
  void credentialsAndSignedTokenAreValidated() {
    assertTrue(sessions.validateCredentials("hhh", "123456"));
    assertFalse(sessions.validateCredentials("hhh", "bad"));
    long now = 1_000_000L;
    String token = sessions.createToken(now);
    assertTrue(sessions.verifyToken(token, now));
    assertFalse(sessions.verifyToken(token + "x", now));
    assertFalse(sessions.verifyToken(token, now + SessionService.LIFETIME.toMillis()));
  }

  @Test
  void authenticatedFindsNamedCookieAndForwardedHttpsSetsSecure() {
    long now = System.currentTimeMillis();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("other", "x"),
        new Cookie(SessionService.COOKIE_NAME, sessions.createToken(now)));
    assertTrue(sessions.authenticated(request));

    request.addHeader("X-Forwarded-Proto", "https");
    assertTrue(sessions.cookie(request, "token", SessionService.LIFETIME).isSecure());
  }
}
