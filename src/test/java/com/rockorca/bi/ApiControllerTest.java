package com.rockorca.bi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    sessions = mock(SessionService.class);
    reports = mock(ReportService.class);
    pets = mock(PetService.class);
    when(sessions.authenticated(any(HttpServletRequest.class))).thenReturn(true);
    mvc = MockMvcBuilders.standaloneSetup(new ApiController(sessions, reports, pets)).build();
  }

  @Test
  void loginAndLogoutReturnSessionCookieContracts() throws Exception {
    when(sessions.validateCredentials("hhh", "123456")).thenReturn(true);
    when(sessions.createToken(any(Long.class))).thenReturn("signed-token");
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
    when(sessions.validateCredentials(anyString(), anyString())).thenReturn(false);

    mvc.perform(post("/api/login")
            .contentType("application/json")
            .content("{\"username\":\"wrong\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("用户名或密码错误"));
  }

  @Test
  void dhhApisDelegateWithDefaultsAndPayload() throws Exception {
    when(reports.currentDhh()).thenReturn(Map.of("source", "current"));
    when(reports.loadDhh("report-token", "20")).thenReturn(Map.of("source", "load"));
    when(reports.analyzeDhh("2026-07-01", "2026-07-25"))
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
            .content("{\"start\":\"2026-07-01\",\"end\":\"2026-07-25\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("analyze"));

    verify(reports).loadDhh("report-token", "20");
    verify(reports).analyzeDhh("2026-07-01", "2026-07-25");
  }

  @Test
  void jdApisDefaultToExcludingUnknownOptimizers() throws Exception {
    when(reports.currentJd()).thenReturn(Map.of("source", "current"));
    when(reports.loadJd("report-token", "20", true)).thenReturn(Map.of("source", "load"));
    when(reports.analyzeJd("2026-07-01", "2026-07-25", false))
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
                {"start":"2026-07-01","end":"2026-07-25","excludeUnknownOptimizer":false}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("analyze"));

    verify(reports).loadJd("report-token", "20", true);
    verify(reports).analyzeJd("2026-07-01", "2026-07-25", false);
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
