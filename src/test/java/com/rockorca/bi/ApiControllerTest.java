package com.rockorca.bi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class ApiControllerTest {
  private SessionService sessions;
  private ReportService reports;
  private JdLowActivityService lowActivityReports;
  private AdpfluxService adpfluxReports;
  private PetService pets;
  private UserService users;
  private UserRepository.UserAccount user;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    sessions = mock(SessionService.class);
    reports = mock(ReportService.class);
    lowActivityReports = mock(JdLowActivityService.class);
    adpfluxReports = mock(AdpfluxService.class);
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
            new ReportApiController(reports, sessions, users),
            new JdLowActivityApiController(lowActivityReports, sessions, users),
            new AdpfluxApiController(adpfluxReports, sessions, users),
            new PetApiController(pets, sessions, users))
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
  void toolVisibilityApisDelegate() throws Exception {
    when(users.effectiveToolVisibility(user)).thenReturn(
        Map.of("todo", true, "terminal", true));
    when(users.saveToolVisibility(eq(user), eq(2L), any())).thenReturn(
        Map.of("todo", true, "terminal", false));

    mvc.perform(get("/api/tool-visibility"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.todo").value(true));
    mvc.perform(post("/api/users/2/tool-visibility")
            .contentType("application/json")
            .content("{\"todo\":true,\"terminal\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.toolVisibility.terminal").value(false));
  }

  @Test
  void dhhApisDelegateWithDefaultsAndPayload() throws Exception {
    when(reports.currentDhh()).thenReturn(Map.of("source", "current"));
    when(reports.savedReportCredentials())
        .thenReturn(Map.of("token", "report-token", "userId", "20"));
    when(reports.reportVisibility())
        .thenReturn(Map.of("dhh", true, "jd", true, "jdLowActivity", true, "adpflux", true));
    when(users.effectiveReportVisibility(eq(user), any()))
        .thenReturn(Map.of("dhh", true, "jd", true, "jdLowActivity", true, "adpflux", true));
    when(reports.saveReportVisibility(true, false, true, true))
        .thenReturn(Map.of("dhh", true, "jd", false, "jdLowActivity", true, "adpflux", true));
    when(reports.saveReportCredentials("new-report-token", "21"))
        .thenReturn(Map.of("configured", true, "userId", "21"));
    when(reports.loadDhh("report-token", "20", "2026-07-01", "2026-07-25"))
        .thenReturn(Map.of("source", "load"));
    when(reports.analyzeDhh("2026-07-01", "2026-07-25", "86784411"))
        .thenReturn(Map.of("source", "analyze"));

    mvc.perform(get("/api/current"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("current"));
    mvc.perform(get("/api/report-credentials"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.token").value("report-token"))
        .andExpect(jsonPath("$.userId").value("20"));
    mvc.perform(get("/api/report-visibility"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.dhh").value(true))
        .andExpect(jsonPath("$.jd").value(true));
    mvc.perform(post("/api/report-visibility")
            .contentType("application/json")
            .content("{\"dhh\":true,\"jd\":false,\"jdLowActivity\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jd").value(false));
    mvc.perform(post("/api/report-credentials")
            .contentType("application/json")
            .content("{\"token\":\"new-report-token\",\"userId\":\"21\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.userId").value("21"));
    mvc.perform(post("/api/load")
            .contentType("application/json")
            .content("""
                {"token":"report-token","userId":"",
                 "start":"2026-07-01","end":"2026-07-25"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("load"));
    mvc.perform(post("/api/analyze")
            .contentType("application/json")
            .content("""
                {"start":"2026-07-01","end":"2026-07-25","accountId":"86784411"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("analyze"));

    verify(reports).loadDhh("report-token", "20", "2026-07-01", "2026-07-25");
    verify(reports).saveReportCredentials("new-report-token", "21");
    verify(reports).saveReportVisibility(true, false, true, true);
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
  void jdLowActivityApisDelegateAndProtectSettings() throws Exception {
    when(lowActivityReports.current()).thenReturn(Map.of("source", "current"));
    when(lowActivityReports.analyze("2026-07-01", "2026-07-31", "账户A", "任务A"))
        .thenReturn(Map.of("source", "analyze"));
    when(lowActivityReports.credentialStatus())
        .thenReturn(Map.of("configured", true, "tokenSaved", true, "signSaved", true));
    when(lowActivityReports.saveCredentials("new-low-token", "B".repeat(40)))
        .thenReturn(Map.of("configured", true, "tokenSaved", true, "signSaved", true));
    when(lowActivityReports.sync(
            "2026-07-01", "2026-07-31", "low-activity-token", "A".repeat(40)))
        .thenReturn(Map.of("source", "sync"));

    mvc.perform(get("/api/jd-low-activity/current"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("current"));
    mvc.perform(post("/api/jd-low-activity/analyze")
            .contentType("application/json")
            .content("""
                {"start":"2026-07-01","end":"2026-07-31",
                 "account":"账户A","task":"任务A"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("analyze"));
    mvc.perform(get("/api/jd-low-activity/settings"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(jsonPath("$.sign").doesNotExist());
    mvc.perform(post("/api/jd-low-activity/settings")
            .contentType("application/json")
            .content("{\"token\":\"new-low-token\",\"sign\":\"" + "B".repeat(40) + "\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.configured").value(true));
    mvc.perform(post("/api/jd-low-activity/sync")
            .contentType("application/json")
            .content("""
                {"start":"2026-07-01","end":"2026-07-31",
                 "token":"low-activity-token","sign":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("sync"));

    verify(lowActivityReports).analyze("2026-07-01", "2026-07-31", "账户A", "任务A");
    verify(lowActivityReports).saveCredentials("new-low-token", "B".repeat(40));
    verify(lowActivityReports).sync(
        "2026-07-01", "2026-07-31", "low-activity-token", "A".repeat(40));
  }

  @Test
  void adpfluxApisDelegateAndProtectCredentials() throws Exception {
    when(adpfluxReports.current()).thenReturn(Map.of("source", "current"));
    when(adpfluxReports.analyze("2026-08-01", "2026-08-20", "云联", "enabled", true))
        .thenReturn(Map.of("source", "analyze"));
    when(adpfluxReports.credentialStatus())
        .thenReturn(Map.of("configured", true, "companyId", "12345678901234567890"));
    when(adpfluxReports.saveCredentials(
        "front-token", "12345678901234567890", "", "", "", "", ""))
        .thenReturn(Map.of("configured", true, "companyId", "12345678901234567890"));
    when(adpfluxReports.sync(
        "2026-08-20", "2026-08-20", "front-token", "12345678901234567890"))
        .thenReturn(Map.of("source", "sync"));

    mvc.perform(get("/api/adpflux/current"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("current"));
    mvc.perform(post("/api/adpflux/analyze")
            .contentType("application/json")
            .content("""
                {"start":"2026-08-01","end":"2026-08-20","query":"云联",
                 "status":"enabled","spendingOnly":true}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("analyze"));
    mvc.perform(get("/api/adpflux/settings"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.companyId").value("12345678901234567890"));
    mvc.perform(post("/api/adpflux/settings")
            .contentType("application/json")
            .content("""
                {"token":"front-token","companyId":"12345678901234567890"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true));
    mvc.perform(post("/api/adpflux/sync")
            .contentType("application/json")
            .content("""
                {"start":"2026-08-20","end":"2026-08-20",
                 "token":"front-token","companyId":"12345678901234567890"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("sync"));

    verify(adpfluxReports).analyze("2026-08-01", "2026-08-20", "云联", "enabled", true);
    verify(adpfluxReports).saveCredentials(
        "front-token", "12345678901234567890", "", "", "", "", "");
    verify(adpfluxReports).sync(
        "2026-08-20", "2026-08-20", "front-token", "12345678901234567890");
  }

  @Test
  void ordinaryUsersCannotTriggerFullReportRefresh() throws Exception {
    UserRepository.UserAccount ordinaryUser = new UserRepository.UserAccount(
        2L, "viewer", "hash", "user", true, 1,
        user.createdAt(), user.updatedAt(), user.lastLoginAt());
    when(sessions.currentUser(any(HttpServletRequest.class))).thenReturn(ordinaryUser);
    org.mockito.Mockito.doThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可以执行此操作"))
        .when(users).requireAdmin(ordinaryUser);

    mvc.perform(post("/api/load")
            .contentType("application/json")
            .content("{\"token\":\"hidden-token\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
    mvc.perform(get("/api/adpflux/settings"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
    mvc.perform(post("/api/adpflux/settings")
            .contentType("application/json")
            .content("{\"token\":\"hidden-token\",\"companyId\":\"12345678901234567890\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
    mvc.perform(post("/api/adpflux/sync")
            .contentType("application/json")
            .content("""
                {"start":"2026-08-20","end":"2026-08-20",
                 "token":"hidden-token","companyId":"12345678901234567890"}
                """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
    mvc.perform(post("/api/jd/load")
            .contentType("application/json")
            .content("{\"token\":\"hidden-token\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
    mvc.perform(get("/api/report-credentials"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
    mvc.perform(post("/api/report-credentials")
            .contentType("application/json")
            .content("{\"token\":\"hidden-token\",\"userId\":\"20\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
    mvc.perform(post("/api/report-visibility")
            .contentType("application/json")
            .content("{\"dhh\":false,\"jd\":false,\"jdLowActivity\":false}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
    mvc.perform(get("/api/jd-low-activity/settings"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
    mvc.perform(post("/api/jd-low-activity/settings")
            .contentType("application/json")
            .content("{\"token\":\"hidden-token\",\"sign\":\"" + "A".repeat(40) + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
    mvc.perform(post("/api/jd-low-activity/sync")
            .contentType("application/json")
            .content("""
                {"start":"2026-07-01","end":"2026-07-31",
                 "token":"hidden-token","sign":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
                """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));

    verify(reports, never()).loadDhh(anyString(), anyString(), anyString(), anyString());
    verify(reports, never()).loadJd(anyString(), anyString(), anyBoolean());
    verify(reports, never()).savedReportCredentials();
    verify(reports, never()).saveReportCredentials(anyString(), anyString());
    verify(reports, never())
        .saveReportVisibility(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
    verify(lowActivityReports, never())
        .sync(anyString(), anyString(), anyString(), anyString());
    verify(lowActivityReports, never()).credentialStatus();
    verify(lowActivityReports, never()).saveCredentials(anyString(), anyString());
    verify(adpfluxReports, never())
        .sync(anyString(), anyString(), anyString(), anyString());
    verify(adpfluxReports, never()).credentialStatus();
    verify(adpfluxReports, never()).saveCredentials(anyString(), anyString());
  }

  @Test
  void petApiDelegatesPayloadAndErrorsUseJsonContract() throws Exception {
    when(pets.chat(any())).thenReturn(Map.of("reply", "分析完成", "mode", "local"));
    when(pets.aiConfigStatus()).thenReturn(
        Map.of("provider", "deepseek", "model", "deepseek-v4-flash", "configured", true));
    when(pets.saveAiConfig("deepseek", "server-api-key", "deepseek-v4-flash"))
        .thenReturn(
            Map.of("provider", "deepseek", "model", "deepseek-v4-flash", "configured", true));

    mvc.perform(post("/api/pet/chat")
            .contentType("application/json")
            .content("{\"message\":\"分析消耗\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("分析完成"));

    mvc.perform(get("/api/pet/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.canManage").value(true))
        .andExpect(jsonPath("$.apiKey").doesNotExist());

    mvc.perform(post("/api/pet/config")
            .contentType("application/json")
            .content("""
                {"provider":"deepseek","apiKey":"server-api-key",
                 "model":"deepseek-v4-flash"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.apiKey").doesNotExist());

    when(reports.currentDhh()).thenThrow(new IllegalArgumentException("日期格式错误"));
    mvc.perform(get("/api/current"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("日期格式错误"));
  }
}
