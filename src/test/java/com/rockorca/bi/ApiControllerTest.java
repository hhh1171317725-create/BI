package com.rockorca.bi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiControllerTest {
  private SessionService sessions;
  private ReportService reports;
  private PetService pets;
  private UserService users;
  private UserRepository.UserAccount user;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    sessions = mock(SessionService.class);
    reports = mock(ReportService.class);
    pets = mock(PetService.class);
    users = mock(UserService.class);
    LocalDateTime now = LocalDateTime.of(2026, 7, 30, 12, 0);
    user = new UserRepository.UserAccount(
        1L, "hhh", "hash", "admin", true, 1, now, now, now);
    when(sessions.authenticated(any(HttpServletRequest.class))).thenReturn(true);
    when(sessions.currentUser(any(HttpServletRequest.class))).thenReturn(user);
    when(users.view(eq(user), anyBoolean())).thenReturn(
        Map.of("id", 1L, "username", "hhh", "role", "admin", "active", true));
    mvc = MockMvcBuilders.standaloneSetup(
            new AuthApiController(sessions, users),
            new AccountApiController(sessions, users),
            new ReportApiController(reports),
            new PetApiController(pets))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void loginAndLogoutReturnSessionCookieContracts() throws Exception {
    when(sessions.authenticate("hhh", "123456")).thenReturn(user);
    when(sessions.createToken(eq(user), anyLong())).thenReturn("signed-token");
    when(sessions.cookie(any(), anyString(), any(Duration.class)))
        .thenReturn(ResponseCookie.from(SessionService.COOKIE_NAME, "signed-token")
            .httpOnly(true).sameSite("Lax").path("/").build());

    mvc.perform(post("/api/login")
            .contentType("application/json")
            .content("{\"username\":\"hhh\",\"password\":\"123456\"}"))
        .andExpect(status().isOk())
        .andExpect(header().exists("Set-Cookie"))
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(jsonPath("$.redirect").value("/"));

    mvc.perform(post("/api/logout"))
        .andExpect(status().isOk())
        .andExpect(header().exists("Set-Cookie"))
        .andExpect(jsonPath("$.ok").value(true));
  }

  @Test
  void invalidLoginIsUnauthorized() throws Exception {
    when(sessions.authenticate(anyString(), anyString())).thenReturn(null);

    mvc.perform(post("/api/login")
            .contentType("application/json")
            .content("{\"username\":\"wrong\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("用户名或密码错误"));
  }

  @Test
  void accountPasswordAndUserManagementApisDelegate() throws Exception {
    UserRepository.UserAccount updated = new UserRepository.UserAccount(
        1L, "hhh", "new-hash", "admin", true, 2,
        user.createdAt(), user.updatedAt(), user.lastLoginAt());
    when(users.changeOwnPassword(user, "123456", "654321")).thenReturn(updated);
    when(users.view(eq(updated), anyBoolean())).thenReturn(
        Map.of("id", 1L, "username", "hhh", "role", "admin", "active", true));
    when(sessions.createToken(eq(updated), anyLong())).thenReturn("renewed-token");
    when(users.listUsers(user)).thenReturn(List.of(
        Map.of("id", 1L, "username", "hhh", "role", "admin", "active", true)));
    when(sessions.cookie(any(), anyString(), any(Duration.class)))
        .thenReturn(ResponseCookie.from(SessionService.COOKIE_NAME, "renewed-token")
            .httpOnly(true).sameSite("Lax").path("/").build());

    mvc.perform(post("/api/account/password")
            .contentType("application/json")
            .content("{\"currentPassword\":\"123456\",\"newPassword\":\"654321\"}"))
        .andExpect(status().isOk())
        .andExpect(header().exists("Set-Cookie"))
        .andExpect(jsonPath("$.ok").value(true));

    mvc.perform(get("/api/users"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users[0].username").value("hhh"));
  }

  @Test
  void dhhApisDelegateWithDefaultsAndPayload() throws Exception {
    when(reports.currentDhh()).thenReturn(Map.of("source", "current"));
    when(reports.loadDhh("report-token", "20")).thenReturn(Map.of("source", "load"));
    when(reports.analyzeDhh("2026-07-01", "2026-07-25", "86784411"))
        .thenReturn(Map.of("source", "analyze"));

    mvc.perform(get("/api/current"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("current"));
    mvc.perform(post("/api/load")
            .contentType("application/json")
            .content("{\"token\":\"report-token\",\"userId\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("load"));
    mvc.perform(post("/api/analyze")
            .contentType("application/json")
            .content("""
                {"start":"2026-07-01","end":"2026-07-25","accountId":"86784411"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("analyze"));

    verify(reports).loadDhh("report-token", "20");
    verify(reports).analyzeDhh("2026-07-01", "2026-07-25", "86784411");
  }

  @Test
  void jdApisDefaultToExcludingUnknownOptimizers() throws Exception {
    when(reports.currentJd()).thenReturn(Map.of("source", "current"));
    when(reports.loadJd("report-token", "20", true)).thenReturn(Map.of("source", "load"));
    when(reports.analyzeJd("2026-07-01", "2026-07-25", false, "1864950618183252"))
        .thenReturn(Map.of("source", "analyze"));

    mvc.perform(get("/api/jd/current"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("current"));
    mvc.perform(post("/api/jd/load")
            .contentType("application/json")
            .content("{\"token\":\"report-token\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("load"));
    mvc.perform(post("/api/jd/analyze")
            .contentType("application/json")
            .content("""
                {"start":"2026-07-01","end":"2026-07-25",
                 "excludeUnknownOptimizer":false,"accountId":"1864950618183252"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("analyze"));

    verify(reports).loadJd("report-token", "20", true);
    verify(reports).analyzeJd(
        "2026-07-01", "2026-07-25", false, "1864950618183252");
  }

  @Test
  void petApiDelegatesPayloadAndErrorsUseJsonContract() throws Exception {
    when(pets.chat(any())).thenReturn(Map.of("reply", "分析完成", "mode", "local"));

    mvc.perform(post("/api/pet/chat")
            .contentType("application/json")
            .content("{\"message\":\"分析消耗\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("分析完成"));

    when(reports.currentDhh()).thenThrow(new IllegalArgumentException("日期格式错误"));
    mvc.perform(get("/api/current"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("日期格式错误"));
  }
}
