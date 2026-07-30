package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class SessionServiceTest {
  private SessionService sessions;
  private UserService users;
  private UserRepository.UserAccount user;

  @BeforeEach
  void setUp() {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.get("REPORT_SESSION_SECRET", "")).thenReturn("test-secret-at-least-32-bytes");
    users = mock(UserService.class);
    LocalDateTime now = LocalDateTime.of(2026, 7, 30, 12, 0);
    user = new UserRepository.UserAccount(
        1L, "hhh", "hash", "admin", true, 1, now, now, null);
    when(users.authenticate("hhh", "123456")).thenReturn(user);
    when(users.findById(1L)).thenReturn(user);
    when(users.findByUsername("hhh")).thenReturn(user);
    sessions = new SessionService(config, new ObjectMapper(), users);
  }

  @Test
  void credentialsAndSignedTokenAreValidated() {
    assertTrue(sessions.authenticate("hhh", "123456") != null);
    assertTrue(sessions.authenticate("hhh", "bad") == null);
    long now = 1_000_000L;
    String token = sessions.createToken(user, now);
    assertTrue(sessions.verifyToken(token, now));
    assertFalse(sessions.verifyToken(token + "x", now));
    assertTrue(sessions.verifyToken(token, now + SessionService.LIFETIME.toMillis()));
  }

  @Test
  void authenticatedFindsNamedCookieAndForwardedHttpsSetsSecure() {
    long now = System.currentTimeMillis();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("other", "x"),
        new Cookie(SessionService.COOKIE_NAME, sessions.createToken(user, now)));
    assertTrue(sessions.authenticated(request));

    request.addHeader("X-Forwarded-Proto", "https");
    assertTrue(sessions.cookie(request, "token", SessionService.LIFETIME).isSecure());
  }

  @Test
  void sessionVersionAndActiveStateInvalidateOldTokens() {
    String token = sessions.createToken(user, System.currentTimeMillis());
    LocalDateTime now = LocalDateTime.of(2026, 7, 30, 12, 0);
    when(users.findById(1L)).thenReturn(new UserRepository.UserAccount(
        1L, "hhh", "hash", "admin", true, 2, now, now, null));
    assertFalse(sessions.verifyToken(token, System.currentTimeMillis()));
  }
}
