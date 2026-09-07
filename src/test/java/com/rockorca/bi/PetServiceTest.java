package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.List;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PetServiceTest {
  private PetService analysisService(ReportRepository repository) {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
    return new PetService(repository, new ReportService(null, null, null, new ObjectMapper()), config, new ObjectMapper());
  }

  private Map<String, Object> row(String name, double spend, double commission) {
    return ReportService.mapOf("日期", "2026-09-01", "优化师", name, "项目", "项目甲", "任务名", "任务甲",
        "消耗", spend, "现金消耗", spend, "预估佣金", commission, "转化数", 5, "注册数", 10);
  }

  @Test
  void naturalDatesAreBoundedAndHandleCalendarEdges() {
    LocalDate today = LocalDate.of(2026, 3, 1);
    assertEquals(List.of("2026-02-01", "2026-02-28"), PetQuery.range("上月", List.of(), today));
    assertEquals(List.of("2026-02-23", "2026-03-01"), PetQuery.range("最近7天", List.of(), today));
    assertEquals(List.of("2026-02-28", "2026-02-28"), PetQuery.range("昨天", List.of(), today));
    assertEquals(List.of("2026-02-01", "2026-02-03"), PetQuery.range("2月1日至2月3日", List.of(), today));
    assertEquals(List.of("2026-02-26", "2026-02-28"), PetQuery.previous(List.of("2026-03-01", "2026-03-03")));
    assertThrows(IllegalArgumentException.class, () -> PetQuery.range("最近0天", List.of(), today));
    assertThrows(IllegalArgumentException.class, () -> PetQuery.range("2026-02-30", List.of(), today));
    assertThrows(IllegalArgumentException.class, () -> PetQuery.range("2026-03-02至2026-03-01", List.of(), today));
    assertThrows(IllegalArgumentException.class, () -> PetQuery.range("总结", List.of("-", "-"), today));
  }

  @SuppressWarnings("unchecked")
  @Test
  void summaryAndFollowUpUseMatchedDataInsteadOfGlobalTotals() {
    PetService service = analysisService(null);
    List<Map<String, Object>> rows = List.of(row("张三", 100, 130), row("李四", 900, 700));
    Map<String, Object> context = Map.of("range", List.of("2026-09-01", "2026-09-01"));
    Map<String, Object> first = service.buildBottomData("张三利润多少", context, rows, false);
    assertEquals(100.0, ((Map<String, Object>) first.get("匹配汇总")).get("消耗"));
    Map<String, Object> next = service.buildBottomData("ROI呢", Map.of("range", context.get("range"),
        "previousConditions", first.get("匹配条件")), rows, false);
    assertEquals(1, next.get("问题匹配行数"));
    assertEquals(1.3, ((Map<String, Object>) next.get("匹配汇总")).get("现金ROI"));
    Map<String, Object> other = service.buildBottomData("李四呢", Map.of("range", context.get("range"),
        "previousConditions", first.get("匹配条件")), rows, false);
    assertEquals(900.0, ((Map<String, Object>) other.get("匹配汇总")).get("消耗"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void accountQueryDoesNotAttributeEntireTaskToOneAccount() {
    Map<String, Object> task = row("张三", 100, 150);
    task.put("账户列表", List.of(Map.of("账户名称", "账户甲", "账户ID", "111", "消耗", 30, "现金消耗", 30),
        Map.of("账户名称", "账户乙", "账户ID", "222", "消耗", 70, "现金消耗", 70)));
    Map<String, Object> data = analysisService(null).buildBottomData("账户甲表现", Map.of(), List.of(task), false);
    Map<String, Object> summary = (Map<String, Object>) data.get("匹配汇总");
    assertEquals(30.0, summary.get("消耗"));
    assertEquals(45.0, summary.get("预估佣金"));
    assertTrue(data.get("说明").toString().contains("分摊"));
  }

  @Test
  void chatReturnsScopeAndHonestLocalModeAndResetsWhenPageChanges() {
    ReportRepository repository = mock(ReportRepository.class);
    when(repository.readDhhRows("2026-09-01", "2026-09-01", "", "")).thenReturn(List.of(row("张三", 100, 130), row("李四", 900, 700)));
    PetService service = analysisService(repository);
    Map<String, Object> context = Map.of("range", List.of("2026-09-01", "2026-09-01"), "reportType", "大航海日报");
    Map<String, Object> first = service.chat(Map.of("message", "张三利润多少", "context", context));
    assertEquals("local", first.get("mode"));
    assertTrue(first.get("notice").toString().contains("未配置"));
    assertTrue(first.get("reply").toString().contains("30"));
    Map<String, Object> next = service.chat(Map.of("message", "ROI呢", "context", context, "queryState", first.get("queryState")));
    assertTrue(next.get("reply").toString().contains("1.3 倍"));
    Map<String, Object> reset = service.chat(Map.of("message", "全部优化师消耗多少", "context", context, "queryState", first.get("queryState")));
    assertTrue(reset.get("reply").toString().contains("1,000"));
  }

  @Test
  void comparisonReportsMissingBaselineInsteadOfInventingGrowth() {
    ReportRepository repository = mock(ReportRepository.class);
    when(repository.readDhhRows("2026-09-01", "2026-09-01", "", "")).thenReturn(List.of(row("张三", 100, 80)));
    when(repository.readDhhRows("2026-08-31", "2026-08-31", "", "")).thenReturn(List.of());
    Map<String, Object> result = analysisService(repository).chat(Map.of("message", "对比上期分析亏损", "context",
        Map.of("range", List.of("2026-09-01", "2026-09-01"), "reportType", "大航海日报")));
    assertTrue(result.get("reply").toString().contains("没有匹配记录，无法计算变化"));
    assertTrue(result.get("reply").toString().contains("张三"));
    verify(repository).readDhhRows("2026-08-31", "2026-08-31", "", "");
  }

  @Test
  void rankingUsesProfitAndExcludesUndefinedRoi() {
    ReportRepository repository = mock(ReportRepository.class);
    when(repository.readDhhRows("2026-09-01", "2026-09-01", "", "")).thenReturn(List.of(
        row("张三", 100, 130), row("李四", 900, 700), row("零成本", 0, 50)));
    PetService service = analysisService(repository);
    Map<String, Object> context = Map.of("range", List.of("2026-09-01", "2026-09-01"), "reportType", "大航海日报");
    String reply = service.chat(Map.of("message", "按利润给优化师排名", "context", context)).get("reply").toString();
    assertTrue(reply.indexOf("张三") < reply.indexOf("李四"));
    reply = service.chat(Map.of("message", "ROI最高的优化师", "context", context)).get("reply").toString();
    assertFalse(reply.contains("零成本"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void jdTotalsUseAllRowsBeforeDetailTruncation() {
    List<Map<String, Object>> rows = new java.util.ArrayList<>();
    for (int i = 0; i < 130; i++) rows.add(ReportService.mapOf("日期", "2026-09-01", "优化师", "张三",
        "消耗", 10, "首购预估佣金", 12, "首购实际佣金", 9, "首购有效订单数", 2,
        "回流有效订单数", 1, "条件内预估赔付金额", 1));
    Map<String, Object> result = analysisService(null).buildBottomData("张三", Map.of(), rows, true);
    Map<String, Object> total = (Map<String, Object>) result.get("匹配汇总");
    assertEquals(true, result.get("明细是否截断"));
    assertEquals(120, result.get("已提供明细行数"));
    assertEquals(1300.0, total.get("消耗"));
    assertEquals(390.0, total.get("预估利润"));
    assertEquals(1.3, total.get("预估ROI"));
    assertEquals(1.0, total.get("实际ROI"));
    assertEquals(390.0, total.get("有效订单数"));
  }

  @Test
  void aiReceivesScopedFactsAndFailuresAreVisibleWithoutLeakingCredentials() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
    server.createContext("/chat/completions", exchange -> {
      requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      byte[] response = "{\"choices\":[{\"message\":{\"content\":\"根据匹配数据，张三现金利润30元。\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(requests.size() == 1 ? 200 : 503, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      RuntimeConfig config = mock(RuntimeConfig.class);
      when(config.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
      when(config.get("AI_PROVIDER", "")).thenReturn("deepseek");
      when(config.get("DEEPSEEK_API_KEY", "")).thenReturn("test-only-key");
      when(config.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
      ReportRepository repository = mock(ReportRepository.class);
      when(repository.readDhhRows("2026-09-01", "2026-09-01", "", "")).thenReturn(List.of(row("张三", 100, 130), row("李四", 900, 700)));
      PetService service = new PetService(repository, new ReportService(null, null, null, new ObjectMapper()), config, new ObjectMapper());
      Map<String, Object> payload = Map.of("message", "张三利润", "context", Map.of("range", List.of("2026-09-01", "2026-09-01"), "reportType", "大航海日报", "summary", Map.of("消耗", 999999)));
      assertEquals("ai", service.chat(payload).get("mode"));
      assertFalse(requests.getFirst().contains("李四"));
      assertFalse(requests.getFirst().contains("999999"));
      Map<String, Object> fallback = service.chat(payload);
      assertEquals("local", fallback.get("mode"));
      assertTrue(fallback.get("notice").toString().contains("暂时不可用"));
      assertFalse(fallback.toString().contains("test-only-key"));
    } finally { server.stop(0); }
  }
  @Test
  void aiConfigStatusNeverReturnsTheApiKey() {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.get("AI_PROVIDER", "")).thenReturn("deepseek");
    when(config.get("DEEPSEEK_API_KEY", "")).thenReturn("sk-server-secret");
    when(config.get("DEEPSEEK_MODEL", "deepseek-v4-flash")).thenReturn("deepseek-chat");
    when(config.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
        .thenReturn("https://api.deepseek.com");
    PetService service = new PetService(null, null, config, new ObjectMapper());

    Map<String, Object> status = service.aiConfigStatus();

    assertEquals("deepseek", status.get("provider"));
    assertEquals("deepseek-chat", status.get("model"));
    assertEquals(true, status.get("configured"));
    assertFalse(status.containsKey("apiKey"));
  }

  @Test
  void savingAiConfigDelegatesToServerRuntimeConfig() {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.get("AI_PROVIDER", "")).thenReturn("openai");
    when(config.get("OPENAI_API_KEY", "")).thenReturn("sk-server-secret");
    when(config.get("OPENAI_MODEL", "gpt-5.6-terra")).thenReturn("gpt-5.6-terra");
    PetService service = new PetService(null, null, config, new ObjectMapper());

    service.saveAiConfig("openai", "sk-server-secret", "gpt-5.6-terra");

    verify(config).saveAiCredentials("openai", "sk-server-secret", "gpt-5.6-terra");
  }
}
