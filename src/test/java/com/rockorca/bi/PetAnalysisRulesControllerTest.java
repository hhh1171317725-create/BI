package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class PetAnalysisRulesControllerTest {
  @TempDir Path directory;
  private final ObjectMapper mapper = new ObjectMapper();
  private SessionService sessions;
  private UserService users;
  private PetAnalysisRulesService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.runtimeDir()).thenReturn(directory);
    service = new PetAnalysisRulesService(config, mapper);
    sessions = mock(SessionService.class);
    users = mock(UserService.class);
    doCallRealMethod().when(users).requireAdmin(any());
    when(sessions.currentUser(any(HttpServletRequest.class))).thenReturn(account("admin", true));
    mvc = MockMvcBuilders.standaloneSetup(new PetAnalysisRulesController(service, sessions, users))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test
  void administratorCanSaveAndReadBothRuleTypesWithVersionMetadata() throws Exception {
    mvc.perform(get("/api/pet/rules"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.updatedAt").isEmpty()).andExpect(jsonPath("$.rules").isEmpty())
        .andExpect(jsonPath("$.canManage").value(true));
    var text = PetAnalysisRulesServiceTest.textRule("all", true, "中文标题", "以现金利润为准");
    var condition = PetAnalysisRulesServiceTest.conditionRule("bid", "plan", "当前出价", "lt", 20.5);
    mvc.perform(post("/api/pet/rules").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("version", 0, "rules", List.of(text, condition)))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.updatedAt").isNumber()).andExpect(jsonPath("$.canManage").value(true))
        .andExpect(jsonPath("$.rules[0].content").value("以现金利润为准"))
        .andExpect(jsonPath("$.rules[1].conditions[0].value").value(20.5));
    verify(users).requireAdmin(account("admin", true));
    mvc.perform(get("/api/pet/rules"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.rules.length()").value(2));
  }

  @Test
  void ordinaryUsersCanReadButCannotWriteAndAnonymousOrInactiveUsersAreRejected() throws Exception {
    var saved = service.save(0, List.of(PetAnalysisRulesServiceTest.textRule("all", true, "规则", "内容")));
    when(sessions.currentUser(any(HttpServletRequest.class))).thenReturn(account("user", true));
    mvc.perform(get("/api/pet/rules"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.canManage").value(false))
        .andExpect(jsonPath("$.rules[0].title").value("规则"));
    mvc.perform(post("/api/pet/rules").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"rules\":[]}"))
        .andExpect(status().isForbidden());
    assertSame(saved, service.snapshot());
    for (UserRepository.UserAccount actor : new UserRepository.UserAccount[] {null, account("admin", false)}) {
      when(sessions.currentUser(any(HttpServletRequest.class))).thenReturn(actor);
      mvc.perform(get("/api/pet/rules")).andExpect(status().isUnauthorized());
      mvc.perform(post("/api/pet/rules").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"rules\":[]}"))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void rejectsFractionalStringMissingNegativeAndOverflowVersionsWithoutCreatingFile() throws Exception {
    for (String json : List.of("{\"version\":0.0,\"rules\":[]}", "{\"version\":\"0\",\"rules\":[]}",
        "{\"rules\":[]}", "{\"version\":-1,\"rules\":[]}",
        "{\"version\":9223372036854775808,\"rules\":[]}",
        "{\"version\":null,\"rules\":[]}", "{\"version\":0,\"rules\":{},\"extra\":true}")) {
      mvc.perform(post("/api/pet/rules").contentType(MediaType.APPLICATION_JSON).content(json))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
    }
    assertFalse(Files.exists(directory.resolve("pet-analysis-rules.json")));
    assertEquals(0, service.snapshot().version());
  }

  @Test
  void validatesStrictRuleAndConditionFieldTypesIncludingFiniteNumericThresholds() throws Exception {
    Map<String, Object> valid = ruleMap();
    for (String field : List.of("id", "title", "scope", "type", "dimension", "content")) {
      Map<String, Object> malformed = new LinkedHashMap<>(valid);
      malformed.put(field, 123);
      badRule(malformed);
    }
    for (Object enabled : List.of("true", 1, List.of())) {
      Map<String, Object> malformed = new LinkedHashMap<>(valid);
      malformed.put("enabled", enabled);
      badRule(malformed);
    }
    var missing = new LinkedHashMap<>(valid);
    missing.remove("enabled");
    badRule(missing);
    var unknown = new LinkedHashMap<>(valid);
    unknown.put("apiKey", "irrelevant");
    badRule(unknown);
    var condition = new LinkedHashMap<>(valid);
    condition.put("type", "condition");
    condition.put("conditions", List.of(Map.of("metric", "消耗", "operator", "gt", "value", "100")));
    badRule(condition);
    condition.put("conditions", List.of(Map.of("metric", "unknown", "operator", "gt", "value", 100)));
    badRule(condition);
    condition.put("conditions", List.of(Map.of("metric", "消耗", "operator", "gt", "value", 100, "extra", true)));
    badRule(condition);
    condition.put("conditions", List.of(Map.of("metric", "消耗", "operator", "gt", "value", 100)));
    String infinite = mapper.writeValueAsString(Map.of("version", 0, "rules", List.of(condition)))
        .replace("\"value\":100", "\"value\":1e309");
    mvc.perform(post("/api/pet/rules").contentType(MediaType.APPLICATION_JSON).content(infinite))
        .andExpect(status().isBadRequest());
    assertEquals(0, service.snapshot().version());
  }

  @Test
  void staleWriteReturns409AndCorruptPersistenceReturns503WithoutOverwriting() throws Exception {
    service.save(0, List.of());
    mvc.perform(post("/api/pet/rules").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"rules\":[]}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("分析规则已被其他页面修改，请重新加载后再保存"));
    Path file = directory.resolve("pet-analysis-rules.json");
    Files.writeString(file, "broken json", StandardCharsets.UTF_8);
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.runtimeDir()).thenReturn(directory);
    var restarted = new PetAnalysisRulesService(config, mapper);
    var brokenMvc = MockMvcBuilders.standaloneSetup(new PetAnalysisRulesController(restarted, sessions, users))
        .setControllerAdvice(new ApiExceptionHandler()).build();
    brokenMvc.perform(get("/api/pet/rules")).andExpect(status().isServiceUnavailable());
    brokenMvc.perform(post("/api/pet/rules").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"rules\":[]}"))
        .andExpect(status().isServiceUnavailable());
    assertEquals("broken json", Files.readString(file, StandardCharsets.UTF_8));
  }

  private void badRule(Map<String, Object> rule) throws Exception {
    mvc.perform(post("/api/pet/rules").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("version", 0, "rules", List.of(rule)))))
        .andExpect(status().isBadRequest());
  }

  private Map<String, Object> ruleMap() {
    var rule = PetAnalysisRulesServiceTest.textRule("all", true, "规则", "内容");
    return new LinkedHashMap<>(Map.of("id", rule.id(), "title", rule.title(), "scope", rule.scope(),
        "enabled", rule.enabled(), "type", rule.type(), "dimension", rule.dimension(),
        "content", rule.content(), "conditions", rule.conditions()));
  }

  private static UserRepository.UserAccount account(String role, boolean active) {
    return new UserRepository.UserAccount(7, "operator", "hash", role, active, 1, null, null, null);
  }
}
