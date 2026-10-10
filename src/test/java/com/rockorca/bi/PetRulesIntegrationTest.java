package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.rockorca.bi.PetAnalysisRulesService.Condition;
import com.rockorca.bi.PetAnalysisRulesService.Rule;
import com.rockorca.bi.PetAnalysisRulesService.Snapshot;

class PetRulesIntegrationTest {
  private static Rule condition(String title, String scope, String dimension, Condition... conditions) {
    return new Rule(UUID.randomUUID().toString(), title, scope, true, "condition", dimension,
        "核对命中对象的实际指标，不直接停投。", List.of(conditions));
  }

  private static Rule text(String title, String scope, boolean enabled, String content) {
    return new Rule(UUID.randomUUID().toString(), title, scope, enabled, "text", "summary", content, List.of());
  }

  private static PetAnalysisRulesService rules(long version, Rule... rules) {
    var service = mock(PetAnalysisRulesService.class);
    when(service.snapshot()).thenReturn(new Snapshot(version, 1L, List.of(rules)));
    return service;
  }

  private static RuntimeConfig config() {
    var config = mock(RuntimeConfig.class);
    when(config.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
    return config;
  }

  private static PetService service(ReportRepository repository, PetAnalysisRulesService rules) {
    return new PetService(repository, new ReportService(null, null, null, new ObjectMapper()), config(), new ObjectMapper(), rules);
  }

  private static Map<String, Object> dailyContext(String type) {
    return Map.of("reportType", type, "range", List.of("2026-09-01", "2026-09-01"));
  }

  private static Map<String, Object> dailyRow(String optimizer, double spend, double commission) {
    return ReportService.mapOf("日期", "2026-09-01", "优化师", optimizer, "项目", "项目甲", "任务名", "任务甲",
        "消耗", spend, "现金消耗", spend, "预估佣金", commission, "转化数", 5, "注册数", 10, "结算数", 5);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> checks(Map<String, Object> response) {
    return (List<Map<String, Object>>) ((Map<String, Object>) response.get("ruleAnalysis")).get("rules");
  }

  @Test void dailyRulesCheckAllGroupsBeforeTop80TruncationAndKeepTheScope() {
    var repository = mock(ReportRepository.class);
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 84; i++) rows.add(dailyRow("优化师" + i, 10, 12));
    rows.add(dailyRow("小消耗亏损者", 1, .8));
    when(repository.readDhhRows("2026-09-01", "2026-09-01", "", "")).thenReturn(rows);
    Rule target = condition("检查亏损优化师", "dhh", "optimizer", new Condition("消耗", "lt", 2), new Condition("ROI", "lt", 1));
    var result = service(repository, rules(7, target, text("别的报表规则", "jd", true, "不应该应用"),
        text("停用规则", "all", false, "不应该应用"))).chat(Map.of("message", "读取当前数据", "context", dailyContext("大航海日报")));
    assertEquals(7L, result.get("rulesVersion"));
    assertEquals(1, checks(result).size());
    assertEquals(85, checks(result).getFirst().get("totalCount"));
    assertEquals(1, checks(result).getFirst().get("hitCount"));
    assertTrue(result.get("reply").toString().contains("小消耗亏损者"));
    assertFalse(result.toString().contains("别的报表规则"));
    verify(repository, times(1)).readDhhRows("2026-09-01", "2026-09-01", "", "");
  }

  @Test void queryFiltersApplyToRulesAndZeroDenominatorIsUnknown() {
    var repository = mock(ReportRepository.class);
    when(repository.readDhhRows("2026-09-01", "2026-09-01", "", "")).thenReturn(List.of(
        dailyRow("张三", 0, 0), dailyRow("李四", 100, 30)));
    Rule target = condition("低ROI", "all", "summary", new Condition("ROI", "lt", 1));
    var result = service(repository, rules(1, target)).chat(Map.of("message", "张三ROI多少", "context", dailyContext("大航海日报")));
    assertEquals(0, checks(result).getFirst().get("hitCount"));
    assertEquals(1, checks(result).getFirst().get("unknownCount"));
    assertTrue(result.get("ruleChecks").toString().contains("分母"));
  }

  @Test void jdUnsupportedPlanDimensionAndMissingMetricsStayUnknown() {
    var repository = mock(ReportRepository.class);
    when(repository.readJdRows("2026-09-01", "2026-09-01", "")).thenReturn(List.of(
        ReportService.mapOf("日期", "2026-09-01", "优化师", "张三", "消耗", 10,
            "首购预估佣金", 5, "计费转化数", 2)));
    var result = service(repository, rules(1,
        condition("缺任务", "jd", "task", new Condition("消耗", "gt", 1)),
        condition("缺结算", "jd", "summary", new Condition("结算数", "eq", 0)),
        condition("佣金别名", "jd", "summary", new Condition("预估佣金", "eq", 5))))
        .chat(Map.of("message", "读取数据", "context", dailyContext("京东日报")));
    assertEquals("no_data", checks(result).get(0).get("status"));
    assertEquals(1, checks(result).get(1).get("unknownCount"));
    assertEquals(1, checks(result).get(2).get("hitCount"));
  }

  @Test void bidUsesDedicatedDimensionDataBeyondTop30AndRejectsExtraFields() {
    var repository = mock(ReportRepository.class);
    Rule target = condition("检查计划", "bid", "plan", new Condition("预估ROI", "lt", 1));
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 100; i++) rows.add(ReportService.mapOf("计划", "计划" + i, "计划ID", "id" + i,
        "消耗", 50, "预估ROI", i == 50 ? .8 : 1.3, "cookie", "NEVER_SEND_SECRET"));
    var context = ReportService.mapOf("mode", "bid", "loaded", true, "range", List.of("2026-09-01", "2026-09-01"),
        "summary", Map.of("消耗", 5000, "计划数", 100), "plans", rows.subList(0, 30),
        "ruleData", Map.of("plan", Map.of("rows", rows, "total", 100)));
    var result = service(repository, rules(8, target)).chat(Map.of("message", "读取当前数据", "context", context));
    assertEquals(100, checks(result).getFirst().get("totalCount"));
    assertEquals(1, checks(result).getFirst().get("hitCount"));
    assertTrue(result.get("ruleChecks").toString().contains("计划50"));
    assertTrue(checks(result).getFirst().get("coverageNote").toString().contains("全部对象"));
    assertFalse(result.toString().contains("NEVER_SEND_SECRET"));
    verifyNoInteractions(repository);
  }

  @Test void bidSampleLimitIsExplicitAndTextRulesAreNotClaimedExecutedWithoutModel() {
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 1002; i++) rows.add(Map.of("计划", "计划" + i, "消耗", 10));
    var context = ReportService.mapOf("mode", "bid", "loaded", true, "range", List.of("2026-09-01", "2026-09-01"),
        "summary", Map.of("消耗", 10020), "ruleData", Map.of("plan", Map.of("rows", rows, "total", 1002)));
    var result = service(mock(ReportRepository.class), rules(2,
        condition("高消耗", "bid", "plan", new Condition("消耗", "gt", 0)),
        text("文字要求", "all", true, "给我分析亏损原因。")))
        .chat(Map.of("message", "读取当前数据", "context", context));
    assertEquals(1000, checks(result).getFirst().get("totalCount"));
    assertTrue(checks(result).getFirst().get("coverageNote").toString().contains("有限样本"));
    assertTrue(result.get("reply").toString().contains("本地未执行"));
    assertFalse(result.get("ruleChecks").toString().contains("本地未执行"));
  }

  @Test void eachNewAnalysisReadsOneAtomicSnapshotAndPageHelpDoesNotApplyReportRules() {
    var repository = mock(ReportRepository.class);
    when(repository.readDhhRows("2026-09-01", "2026-09-01", "", "")).thenReturn(List.of(dailyRow("张三", 10, 8)));
    var rules = mock(PetAnalysisRulesService.class);
    when(rules.snapshot()).thenReturn(new Snapshot(1, 1L, List.of(condition("版本一", "all", "summary", new Condition("消耗", "gt", 5)))),
        new Snapshot(2, 2L, List.of()));
    var service = service(repository, rules);
    var body = Map.<String, Object>of("message", "读取数据", "context", dailyContext("大航海日报"));
    assertEquals(1L, service.chat(body).get("rulesVersion"));
    assertEquals(2L, service.chat(body).get("rulesVersion"));
    var page = service.chat(Map.of("message", "解释ROI", "context", Map.of("mode", "page", "pagePath", "/tools")));
    assertFalse(page.containsKey("rulesApplied"));
    verify(rules, times(2)).snapshot();
  }

  @Test void bothModelProvidersReceiveServerRulesAndDeterministicEvidenceInDedicatedInstructions() throws Exception {
    var requests = new CopyOnWriteArrayList<String>();
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    for (String path : List.of("/chat/completions", "/responses")) server.createContext(path, exchange -> {
      requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String json = exchange.getRequestURI().getPath().equals("/responses")
          ? "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"按保存规则分析\"}]}]}"
          : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"按保存规则分析\"}}]}";
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try {
      for (String provider : List.of("deepseek", "openai")) {
        var repository = mock(ReportRepository.class);
        when(repository.readDhhRows("2026-09-01", "2026-09-01", "", "")).thenReturn(List.of(dailyRow("张三", 100, 80)));
        var rules = rules(4, text("保存的文字要求", "all", true, "先讲现金利润，再解释注册成本。"),
            condition("保存的条件", "dhh", "summary", new Condition("ROI", "lt", 1)));
        var service = new PetService(repository, new ReportService(null, null, null, new ObjectMapper()), config(), new ObjectMapper(), rules) {
          @Override public AiConfig resolveAiConfig() {
            return new AiConfig(provider, "test-only-key", "test-model", "http://127.0.0.1:" + server.getAddress().getPort());
          }
        };
        var result = service.chat(Map.of("message", "读取数据", "context", dailyContext("大航海日报"),
            "rules", List.of(Map.of("content", "UNTRUSTED_CLIENT_RULE"))));
        assertEquals("ai", result.get("mode"));
        Map<?, ?> body = new ObjectMapper().readValue(requests.getLast(), Map.class);
        Object instructions = provider.equals("openai") ? body.get("instructions") : ((Map<?, ?>)((List<?>)body.get("messages")).getFirst()).get("content");
        assertTrue(instructions.toString().contains("先讲现金利润，再解释注册成本。"));
        assertTrue(requests.getLast().contains("hitCount"));
        assertFalse(requests.getLast().contains("UNTRUSTED_CLIENT_RULE"));
        assertFalse(result.toString().contains("test-only-key"));
        assertFalse(result.get("ruleChecks").toString().contains("本地未执行"));
      }
    } finally { server.stop(0); }
  }
}
