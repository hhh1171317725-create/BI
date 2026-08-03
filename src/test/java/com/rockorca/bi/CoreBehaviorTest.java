package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class CoreBehaviorTest {
  private CsvImportService importer;
  private ReportService reports;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    importer = new CsvImportService(objectMapper);
    reports = new ReportService(null, importer, null, objectMapper);
  }

  @Test
  void environmentParserSupportsCommentsQuotesAndEqualsSigns() {
    Map<String, String> parsed = RuntimeConfig.parseEnvironmentFile("""
        # MySQL connection
        MYSQL_HOST=127.0.0.1
        MYSQL_PASSWORD="p@ss=word#2026"
        invalid-key=ignored
        """);

    assertEquals(Map.of(
        "MYSQL_HOST", "127.0.0.1",
        "MYSQL_PASSWORD", "p@ss=word#2026"), parsed);
  }

  @Test
  void savedReportCredentialsAreReadFromTheServerRuntimeDirectory(
      @TempDir Path runtimeDirectory) throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    RuntimeConfig config = new RuntimeConfig(objectMapper);
    ReflectionTestUtils.setField(config, "runtimeDir", runtimeDirectory);
    Files.writeString(
        runtimeDirectory.resolve("scheduler-credentials.json"),
        "{\"token\":\"server-report-token\",\"userId\":\"20\"}");
    ReportService service = new ReportService(null, null, config, objectMapper);

    assertEquals(
        Map.of("token", "server-report-token", "userId", "20"),
        service.savedReportCredentials());
  }

  @Test
  void reportCredentialsCanBeSavedWithoutRunningAReportRefresh(
      @TempDir Path runtimeDirectory) throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    RuntimeConfig config = new RuntimeConfig(objectMapper);
    ReflectionTestUtils.setField(config, "runtimeDir", runtimeDirectory);
    ReportService service = new ReportService(null, null, config, objectMapper);

    assertEquals(
        Map.of("configured", true, "userId", "21"),
        service.saveReportCredentials("server-report-token", "21"));
    assertEquals(
        Map.of("token", "server-report-token", "userId", "21"),
        service.savedReportCredentials());

    service.saveReportCredentials("", "");
    assertEquals(
        Map.of("token", "server-report-token", "userId", "21"),
        service.savedReportCredentials());
  }

  @Test
  void csvParserSupportsQuotedCommasAndEscapedQuotes() {
    List<Map<String, String>> rows = CsvImportService.parseCsv(
        "名称,备注,金额\r\n账户A,\"包含,逗号\",100\r\n账户B,\"含\"\"引号\",200"
            + "\r\n账户C,\"跨行\n备注\",300");

    assertEquals(3, rows.size());
    assertEquals("包含,逗号", rows.getFirst().get("备注"));
    assertEquals("含\"引号", rows.get(1).get("备注"));
    assertEquals("跨行\n备注", rows.getLast().get("备注"));
  }

  @Test
  void dhhAccountJsonKeepsIdentityAndSpend() {
    List<Map<String, Object>> accounts = importer.parseDhhAccounts("""
        [{
          "media_ad_account_id":"86784411",
          "media_ad_account_name":"0723.1亿典促购",
          "media_total_cost":"123.45",
          "media_cash_cost":"100",
          "media_reward_cost":"23.45"
        }]
        """);

    assertEquals(1, accounts.size());
    assertEquals("86784411", accounts.getFirst().get("账户ID"));
    assertEquals("0723.1亿典促购", accounts.getFirst().get("账户名称"));
    assertEquals(123.45, accounts.getFirst().get("消耗"));
  }

  @Test
  void defaultDateRangeStartsAtCurrentBeijingMonth() {
    assertEquals("2026-07-01",
        ReportService.beijingMonthStart(Instant.parse("2026-07-31T15:59:59Z")));
    assertEquals("2026-08-01",
        ReportService.beijingMonthStart(Instant.parse("2026-07-31T16:00:00Z")));
    assertEquals("2026-07-24",
        ReportService.previousBeijingDate(Instant.parse("2026-07-25T12:00:00Z")));
  }

  @Test
  void alertsUseAccountOptimizerProjectAndTaskDimensions() {
    Map<String, Object> account = map(
        "账户ID", "1",
        "账户名称", "普通账户",
        "消耗", 120);
    List<Map<String, Object>> rows = List.of(
        map("日期", "2026-07-23", "优化师", "优化师A", "项目", "项目A",
            "任务名", "任务A", "注册数", 0, "结算数", 0, "账户列表", List.of(account)),
        map("日期", "2026-07-23", "优化师", "优化师B", "项目", "项目B",
            "任务名", "任务B", "注册数", 100, "结算数", 89, "账户列表", List.of(account)));

    Map<String, Object> result = reports.buildDhhAlerts(rows, "2026-07-23");
    List<Map<String, Object>> items = maps(result.get("items"));

    assertEquals(2, result.get("total"));
    assertEquals(List.of("优化师A", "优化师B"),
        items.stream().map(item -> String.valueOf(item.get("优化师"))).sorted().toList());
    assertEquals(List.of("项目A", "项目B"),
        items.stream().map(item -> String.valueOf(item.get("项目"))).sorted().toList());
    assertEquals(List.of("任务A", "任务B"),
        items.stream().map(item -> String.valueOf(item.get("任务名"))).sorted().toList());
  }

  @Test
  void settlementAlertFiresOnlyWhenSettlementIsMoreThanTenPercentLower() {
    Map<String, Object> account = map("账户ID", "1", "账户名称", "普通账户", "消耗", 10);
    List<Map<String, Object>> rows = List.of(
        map("日期", "2026-07-23", "任务名", "任务A",
            "注册数", 100, "结算数", 90, "账户列表", List.of(account)),
        map("日期", "2026-07-23", "任务名", "任务B",
            "注册数", 100, "结算数", 89, "账户列表", List.of(account)));

    Map<String, Object> result = reports.buildDhhAlerts(rows, "2026-07-23");
    List<Map<String, Object>> items = maps(result.get("items"));

    assertEquals(1, result.get("total"));
    assertEquals("任务B", items.getFirst().get("任务名"));
    assertEquals("settlements_below_registrations_10pct",
        maps(items.getFirst().get("reasons")).getFirst().get("code"));
  }

  @Test
  void xianyuAccountsAndTasksAreExcludedFromAlerts() {
    List<Map<String, Object>> rows = List.of(
        map("日期", "2026-07-23", "任务名", "闲鱼促活",
            "注册数", 0, "结算数", 0, "账户列表",
            List.of(map("账户ID", "1", "账户名称", "普通账户", "消耗", 200))),
        map("日期", "2026-07-23", "任务名", "普通任务",
            "注册数", 0, "结算数", 0, "账户列表",
            List.of(map("账户ID", "2", "账户名称", "咸鱼账户", "消耗", 200))));

    Map<String, Object> result = reports.buildDhhAlerts(rows, "2026-07-23");

    assertEquals(0, result.get("total"));
  }

  @Test
  void jdMetricsAndEveryDateDrilldownRespectUnknownOptimizerFilter() {
    List<Map<String, Object>> rows = List.of(
        jdRow("2026-07-23", "陈灵灿", "京东", "账户A", "A1", "推客A", 10, 6, 4),
        jdRow("2026-07-23", "unknown", "京东", "账户B", "B1", "推客B", 100, 80, 20));

    Map<String, Object> analysis =
        reports.buildJdAnalysis(rows, "2026-07-01", "2026-07-31", true, "now");
    Map<String, Object> summary = object(analysis.get("summary"));

    assertEquals(10.0, summary.get("消耗"));
    assertEquals(10.0, summary.get("有效订单数"));
    assertEquals(0.6, summary.get("有效首购率"));
    assertEquals(1, maps(analysis.get("by_optimizer_date")).size());
    assertEquals(1, maps(analysis.get("by_account_date")).size());
    assertEquals(1, maps(analysis.get("by_media_date")).size());
    assertEquals(1, maps(analysis.get("by_promoter_date")).size());
    assertTrue(Boolean.TRUE.equals(analysis.get("excludeUnknownOptimizer")));
    assertFalse(maps(analysis.get("by_account_date")).isEmpty());
  }

  @Test
  void projectAndTaskDrilldownsKeepOptimizerMetrics() {
    List<Map<String, Object>> rows = List.of(
        map("日期", "2026-07-23", "优化师", "优化师A", "项目", "项目A", "任务名", "任务A",
            "消耗", 100, "现金消耗", 80, "预估佣金", 120),
        map("日期", "2026-07-23", "优化师", "优化师B", "项目", "项目A", "任务名", "任务A",
            "消耗", 200, "现金消耗", 160, "预估佣金", 240));

    Map<String, Object> analysis =
        reports.buildDhhAnalysis(rows, "2026-07-01", "2026-07-31", "now");
    List<Map<String, Object>> byProject = maps(analysis.get("by_optimizer_project_date"));
    List<Map<String, Object>> byTask = maps(analysis.get("by_optimizer_task_date"));
    List<Map<String, Object>> projectOptimizers = maps(analysis.get("by_optimizer_project"));
    List<Map<String, Object>> taskOptimizers = maps(analysis.get("by_optimizer_task"));

    assertEquals(List.of("优化师A", "优化师B"),
        byProject.stream().map(item -> String.valueOf(item.get("优化师"))).sorted().toList());
    assertEquals(List.of("优化师A", "优化师B"),
        byTask.stream().map(item -> String.valueOf(item.get("优化师"))).sorted().toList());
    assertEquals(List.of("优化师A", "优化师B"),
        projectOptimizers.stream().map(item -> String.valueOf(item.get("优化师"))).sorted().toList());
    assertEquals(List.of("优化师A", "优化师B"),
        taskOptimizers.stream().map(item -> String.valueOf(item.get("优化师"))).sorted().toList());
    assertEquals(100.0, projectOptimizers.stream()
        .filter(item -> "优化师A".equals(item.get("优化师"))).findFirst().orElseThrow().get("消耗"));
    assertEquals(40.0, taskOptimizers.stream()
        .filter(item -> "优化师A".equals(item.get("优化师"))).findFirst().orElseThrow().get("现金利润"));
  }

  @Test
  void dhhAccountDimensionUsesAccountSpendAndAllocatesTaskMetricsOnce() {
    List<Map<String, Object>> accounts = List.of(
        map("账户ID", "A1", "账户名称", "账户A",
            "消耗", 75, "现金消耗", 60, "赠款消耗", 15),
        map("账户ID", "B1", "账户名称", "账户B",
            "消耗", 25, "现金消耗", 20, "赠款消耗", 5));
    List<Map<String, Object>> rows = List.of(
        map("日期", "2026-07-23", "优化师", "优化师A", "项目", "项目A", "任务名", "任务A",
            "消耗", 100, "现金消耗", 80, "赠款消耗", 20,
            "预估佣金", 200, "结算数", 40, "转化数", 20, "注册数", 10,
            "账户列表", accounts));

    Map<String, Object> analysis =
        reports.buildDhhAnalysis(rows, "2026-07-01", "2026-07-31", "now");
    List<Map<String, Object>> byAccount = maps(analysis.get("by_account"));
    Map<String, Object> accountA = byAccount.stream()
        .filter(item -> "A1".equals(item.get("账户ID")))
        .findFirst()
        .orElseThrow();

    assertEquals(2, byAccount.size());
    assertEquals("账户A", accountA.get("账户名称"));
    assertEquals(75.0, accountA.get("消耗"));
    assertEquals(150.0, accountA.get("预估佣金"));
    assertEquals(30.0, accountA.get("结算数"));
    assertEquals(200.0, byAccount.stream()
        .mapToDouble(item -> ((Number) item.get("预估佣金")).doubleValue())
        .sum());
    assertEquals(2, maps(analysis.get("by_account_date")).size());
    assertEquals(2, maps(analysis.get("by_optimizer_account")).size());
    assertEquals(2, maps(analysis.get("by_optimizer_account_date")).size());
  }

  private static Map<String, Object> jdRow(
      String date,
      String optimizer,
      String media,
      String account,
      String accountId,
      String promoter,
      double spend,
      double firstEffective,
      double returnEffective) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("日期", date);
    row.put("优化师", optimizer);
    row.put("媒体", media);
    row.put("媒体账户名称", account);
    row.put("媒体账户ID", accountId);
    row.put("推客用户名", promoter);
    for (String field : CsvImportService.JD_NUMERIC_FIELDS) row.put(field, 0d);
    row.put("消耗", spend);
    row.put("首购有效订单数", firstEffective);
    row.put("回流有效订单数", returnEffective);
    return row;
  }

  private static Map<String, Object> map(Object... values) {
    return ReportService.mapOf(values);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
