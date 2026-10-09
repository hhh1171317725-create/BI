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
  @Test void bidReportUsesCurrentPageDataWithoutReadingOtherReports() {
    ReportRepository repository=mock(ReportRepository.class);
    var result=analysisService(repository).chat(Map.of("message","读取页面内容","context",Map.of(
        "mode","bid","loaded",true,"range",List.of("2026-09-09","2026-09-09"),
        "summary",Map.of("计划数",2,"消耗",150,"转化数",10,"注册数",100,"价格匹配计划数",1),
        "cookie","secret-cookie","plans",List.of(Map.of("计划","测试计划","计划ID","123","消耗",100)))));
    assertEquals("local",result.get("mode"));
    assertTrue(result.get("scope").toString().contains("当前筛选结果"));
    assertTrue(result.get("reply").toString().contains("150"));
    assertTrue(result.get("reply").toString().contains("测试计划"));
    assertFalse(result.toString().contains("secret-cookie"));
    org.mockito.Mockito.verifyNoInteractions(repository);
  }
  @Test void unloadedBidReportAsksToLoadData() {
    var result=analysisService(mock(ReportRepository.class)).chat(Map.of("message","分析","context",Map.of("mode","bid","loaded",false)));
    assertEquals("clarification",result.get("mode"));
  }
  @Test
  void explicitlyUnloadedDailyReportDoesNotReadBottomRows() {
    ReportRepository repository = mock(ReportRepository.class);
    Map<String, Object> result = analysisService(repository).chat(Map.of("message", "分析利润", "context",
        Map.of("loaded", false, "range", List.of("2026-09-01", "2026-09-01"), "reportType", "大航海日报")));
    assertEquals("clarification", result.get("mode"));
    assertTrue(result.get("reply").toString().contains("先查询数据"));
    org.mockito.Mockito.verifyNoInteractions(repository);
  }
  @Test
  void pageHelpDoesNotReadReportsOrTrustArbitraryPageContents() {
    ReportRepository repository = mock(ReportRepository.class);
    Map<String, Object> result = analysisService(repository).chat(Map.of("message", "ROI是什么意思", "context",
        Map.of("mode", "page", "pagePath", "/bid-monitor.html", "password", "must-not-appear")));
    assertEquals("local", result.get("mode"));
    assertTrue(result.get("scope").toString().contains("出价监测"));
    assertTrue(result.get("reply").toString().contains("ROI=收益÷成本"));
    assertFalse(result.toString().contains("must-not-appear"));
    org.mockito.Mockito.verifyNoInteractions(repository);
  }
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
      byte[] response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"根据匹配数据，张三现金利润30元。\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
  void incompleteEmptyAndUnavailableAiAnswersUseAccurateNoticesInAllModes() throws Exception {
    var response = new java.util.concurrent.atomic.AtomicReference<>("");
    var status = new java.util.concurrent.atomic.AtomicInteger(200);
    var server = localAiServer(response, status, new java.util.concurrent.CopyOnWriteArrayList<>());
    server.start();
    try {
      ReportRepository repository = mock(ReportRepository.class);
      when(repository.readDhhRows("2026-09-01", "2026-09-01", "", ""))
          .thenReturn(List.of(row("张三", 100, 130)));
      PetService service = localAiService(repository, server);
      List<Map<String, Object>> contexts = List.of(
          Map.of("range", List.of("2026-09-01", "2026-09-01"), "reportType", "大航海日报"),
          Map.of("mode", "bid", "loaded", true, "range", List.of("2026-09-01", "2026-09-01"),
              "summary", Map.of("消耗", 100, "注册数", 10)),
          Map.of("mode", "page", "pagePath", "/tools.html"));
      List<Map<String, Object>> cases = List.of(
          Map.of("body", deepseekResponse("length", "不完整的模型回答"), "status", 200, "notice", "被截断"),
          Map.of("body", deepseekResponse("content_filter", "不完整的模型回答"), "status", 200, "notice", "未能提供此问题"),
          Map.of("body", deepseekResponse("tool_calls", "不完整的模型回答"), "status", 200, "notice", "未完整生成"),
          Map.of("body", deepseekResponse("stop", "  "), "status", 200, "notice", "未返回回答"),
          Map.of("body", "{\"error\":{\"message\":\"test-only-key must remain private\"}}", "status", 200, "notice", "暂时不可用"),
          Map.of("body", deepseekResponse("stop", "不完整的模型回答"), "status", 503, "notice", "暂时不可用"));
      for (Map<String, Object> testCase : cases) {
        response.set(testCase.get("body").toString());
        status.set((Integer) testCase.get("status"));
        for (Map<String, Object> context : contexts) {
          Map<String, Object> result = service.chat(Map.of("message", "消耗多少", "context", context));
          assertEquals("local", result.get("mode"), testCase + " / " + context);
          assertTrue(result.get("notice").toString().contains(testCase.get("notice").toString()), result.toString());
          assertFalse(result.get("notice").toString().contains("未配置"));
          assertFalse(result.toString().contains("不完整的模型回答"));
          assertFalse(result.toString().contains("test-only-key"));
        }
      }
    } finally { server.stop(0); }
  }

  @Test
  void openAiResponsesRequireCompletedTextWithoutErrorsOrRefusals() {
    Map<String, Object> output = Map.of("type", "message", "status", "completed", "content",
        List.of(Map.of("type", "output_text", "text", "完整回答")));
    PetService.AiAnswer answer = PetService.parseAiAnswer("openai", Map.of("status", "completed", "output", List.of(output)));
    assertEquals("完整回答", answer.text());
    assertEquals(null, answer.failure());
    assertEquals(PetService.AiFailure.INCOMPLETE, PetService.parseAiAnswer("openai",
        Map.of("status", "incomplete", "incomplete_details", Map.of("reason", "max_output_tokens"), "output", List.of(output))).failure());
    assertEquals(PetService.AiFailure.INCOMPLETE, PetService.parseAiAnswer("openai",
        Map.of("status", "completed", "output", List.of(Map.of("status", "incomplete", "content", output.get("content"))))).failure());
    assertEquals(PetService.AiFailure.UNAVAILABLE, PetService.parseAiAnswer("openai",
        Map.of("status", "completed", "error", Map.of("message", "private provider error"), "output", List.of(output))).failure());
    assertEquals(PetService.AiFailure.UNAVAILABLE, PetService.parseAiAnswer("openai", Map.of("status", "failed")).failure());
    assertEquals(PetService.AiFailure.REFUSED, PetService.parseAiAnswer("openai", Map.of("status", "completed", "output",
        List.of(Map.of("content", List.of(Map.of("type", "refusal", "refusal", "拒绝内容"),
            Map.of("type", "output_text", "text", "不能作为完整答案")))))).failure());
    assertEquals(PetService.AiFailure.REFUSED, PetService.parseAiAnswer("openai",
        Map.of("status", "incomplete", "incomplete_details", Map.of("reason", "content_filter"))).failure());
    assertEquals(PetService.AiFailure.EMPTY, PetService.parseAiAnswer("openai", Map.of("status", "completed", "output", List.of())).failure());
    assertEquals("", PetService.parseAiAnswer("openai", Map.of("status", "incomplete", "output", List.of(output))).text());
  }

  @SuppressWarnings("unchecked")
  @Test
  void bidAiReceivesCurrentMetricDefinitionsAndWhitelistedFacts() throws Exception {
    var response = new java.util.concurrent.atomic.AtomicReference<>(deepseekResponse("stop", "**结论**：请核对注册成本。"));
    var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var server = localAiServer(response, new java.util.concurrent.atomic.AtomicInteger(200), requests);
    server.start();
    try {
      ReportRepository repository = mock(ReportRepository.class);
      Map<String, Object> metrics = Map.of("消耗", 100, "注册数", 10, "注册成本", 10, "预估eCPM", 12,
          "预估赔付", 20, "cookie", "private-cookie");
      Map<String, Object> result = localAiService(repository, server).chat(Map.of("message", "分析注册成本", "context",
          Map.of("mode", "bid", "loaded", true, "range", List.of("2026-09-01", "2026-09-01"),
              "summary", metrics, "plans", List.of(metrics), "anomalies", List.of(metrics))));
      assertEquals("ai", result.get("mode"));
      assertFalse(requests.getFirst().contains("private-cookie"));
      ObjectMapper mapper = new ObjectMapper();
      Map<String, Object> request = mapper.readValue(requests.getFirst(), Map.class);
      List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
      assertTrue(messages.getFirst().get("content").toString().contains("简洁Markdown"));
      assertTrue(messages.getFirst().get("content").toString().contains("关键证据"));
      String content = messages.getLast().get("content").toString();
      Map<String, Object> context = mapper.readValue(content.substring("报表上下文：".length(), content.indexOf("\n\n用户问题：")), Map.class);
      for (Map<String, Object> facts : List.of((Map<String, Object>) context.get("汇总"),
          ((List<Map<String, Object>>) context.get("消耗最高计划（最多30条）")).getFirst(),
          ((List<Map<String, Object>>) context.get("异常计划（最多20条）")).getFirst())) {
        assertEquals(10, facts.get("注册成本"));
        assertEquals(12, facts.get("预估eCPM"));
        assertEquals(20, facts.get("预估赔付"));
      }
      String definitions = context.get("口径").toString();
      assertTrue(definitions.contains("无日报任务时解码open_url"));
      assertTrue(definitions.contains("注册成本=消耗÷注册数"));
      assertTrue(definitions.contains("历史按各数据日期D计算后合并"));
      assertTrue(definitions.contains("不能平均逐日gap"));
      assertTrue(definitions.contains("估算曝光"));
      assertFalse(definitions.contains("实际单价最接近"));
      org.mockito.Mockito.verifyNoInteractions(repository);
    } finally { server.stop(0); }
  }

  private static String deepseekResponse(String finish, String content) {
    return new ObjectMapper().writeValueAsString(Map.of("choices", List.of(Map.of(
        "finish_reason", finish, "message", Map.of("content", content)))));
  }

  private static com.sun.net.httpserver.HttpServer localAiServer(
      java.util.concurrent.atomic.AtomicReference<String> response,
      java.util.concurrent.atomic.AtomicInteger status,
      List<String> requests) throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      byte[] bytes = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    return server;
  }

  private PetService localAiService(ReportRepository repository, com.sun.net.httpserver.HttpServer server) {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
    when(config.get("AI_PROVIDER", "")).thenReturn("deepseek");
    when(config.get("DEEPSEEK_API_KEY", "")).thenReturn("test-only-key");
    when(config.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
        .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
    return new PetService(repository, new ReportService(null, null, null, new ObjectMapper()), config, new ObjectMapper());
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
