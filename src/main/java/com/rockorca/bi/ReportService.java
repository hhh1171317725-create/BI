package com.rockorca.bi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
  public static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
  public static final List<String> DHH_NUMERIC_FIELDS =
      List.of("消耗", "现金消耗", "赠款消耗", "预估佣金", "结算数", "转化数", "注册数");

  private final ReportRepository repository;
  private final CsvImportService importer;
  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final AtomicBoolean refreshRunning = new AtomicBoolean(false);

  public ReportService(
      ReportRepository repository,
      CsvImportService importer,
      RuntimeConfig config,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.importer = importer;
    this.config = config;
    this.objectMapper = objectMapper;
  }

  public Map<String, Object> currentDhh() {
    return buildDhhAnalysis(
        repository.readDhhRows(), beijingMonthStart(Instant.now()), "", repository.latestSyncTime("dhh"));
  }

  public Map<String, Object> analyzeDhh(String start, String end) {
    return buildDhhAnalysis(
        repository.readDhhRows(), text(start), text(end), repository.latestSyncTime("dhh"));
  }

  public Map<String, Object> loadDhh(String token, String userId) {
    return runExclusiveRefresh(() -> {
      List<Map<String, Object>> rows = importer.fetchDhhRows(token, userId);
      // 凭据文件先原子写入；若保存失败，不开始破坏性的全量表替换。
      saveSchedulerCredentials(token, userId);
      repository.replaceOne("dhh", rows, "manual");
      return currentDhh();
    });
  }

  public Map<String, Object> currentJd() {
    return buildJdAnalysis(
        repository.readJdRows(), beijingMonthStart(Instant.now()), "", true,
        repository.latestSyncTime("jd"));
  }

  public Map<String, Object> analyzeJd(String start, String end, boolean excludeUnknownOptimizer) {
    return buildJdAnalysis(
        repository.readJdRows(), text(start), text(end), excludeUnknownOptimizer,
        repository.latestSyncTime("jd"));
  }

  public Map<String, Object> loadJd(
      String token, String userId, boolean excludeUnknownOptimizer) {
    return runExclusiveRefresh(() -> {
      List<Map<String, Object>> rows = importer.fetchJdRows(token, userId);
      saveSchedulerCredentials(token, userId);
      repository.replaceOne("jd", rows, "manual");
      return buildJdAnalysis(
          repository.readJdRows(), beijingMonthStart(Instant.now()), "", excludeUnknownOptimizer,
          repository.latestSyncTime("jd"));
    });
  }

  @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Shanghai")
  public void scheduledRefresh() {
    try {
      refreshAllReports();
      System.out.println("定时全量更新成功：" + ZonedDateTime.now(BEIJING));
    } catch (Exception error) {
      System.err.println("定时全量更新失败：" + error.getMessage());
    }
  }

  public void refreshAllReports() {
    runExclusiveRefresh(() -> {
      Map<String, String> credentials = readSchedulerCredentials();
      // 两份上游数据必须都拉取成功，随后才在一个数据库事务中同时替换。
      List<Map<String, Object>> dhhRows =
          importer.fetchDhhRows(credentials.get("token"), credentials.get("userId"));
      List<Map<String, Object>> jdRows =
          importer.fetchJdRows(credentials.get("token"), credentials.get("userId"));
      repository.replaceAll(dhhRows, jdRows, "scheduled");
      return null;
    });
  }

  private <T> T runExclusiveRefresh(Supplier<T> action) {
    // 手动更新和 09:00 定时更新共用同一把进程锁，避免 DELETE+INSERT 互相覆盖。
    if (!refreshRunning.compareAndSet(false, true)) {
      throw new IllegalStateException("已有全量更新任务正在运行");
    }
    try {
      return action.get();
    } finally {
      refreshRunning.set(false);
    }
  }

  public Map<String, Object> buildDhhAnalysis(
      List<Map<String, Object>> sourceRows, String start, String end, String cachedAt) {
    List<Map<String, Object>> filtered = sourceRows.stream()
        .filter(row -> inRange(row, start, end))
        .toList();
    List<Map<String, Object>> byProject = aggregateDhh(filtered, List.of("项目"));
    Map<String, Object> summary = zeroValues(DHH_NUMERIC_FIELDS);
    for (String field : DHH_NUMERIC_FIELDS) {
      double total = byProject.stream().mapToDouble(item -> number(item.get(field))).sum();
      summary.put(field, round(total, 2));
    }
    double spend = number(summary.get("消耗"));
    double cash = number(summary.get("现金消耗"));
    double commission = number(summary.get("预估佣金"));
    summary.put("现金利润", round(commission - cash, 2));
    summary.put("ROI", spend == 0 ? 0 : round(commission / spend, 4));
    summary.put("现金ROI", cash == 0 ? 0 : round(commission / cash, 4));

    Map<String, Object> response = baseAnalysis(filtered, cachedAt);
    response.put("summary", summary);
    response.put("alerts", buildDhhAlerts(sourceRows, previousBeijingDate(Instant.now())));
    response.put("by_optimizer", aggregateDhh(filtered, List.of("优化师")));
    response.put("by_project", byProject);
    response.put("by_date", dateDescending(aggregateDhh(filtered, List.of("日期"))));
    response.put("by_task", aggregateDhh(filtered, List.of("任务名")));
    response.put("by_optimizer_date", aggregateDhh(filtered, List.of("日期", "优化师")));
    response.put("by_optimizer_project_date",
        aggregateDhh(filtered, List.of("日期", "优化师", "项目")));
    response.put("by_optimizer_task_date",
        aggregateDhh(filtered, List.of("日期", "优化师", "任务名")));
    response.put("by_optimizer_project_task_date",
        aggregateDhh(filtered, List.of("日期", "优化师", "项目", "任务名")));
    response.put("by_project_date", aggregateDhh(filtered, List.of("日期", "项目")));
    response.put("by_task_date", aggregateDhh(filtered, List.of("日期", "任务名")));
    return response;
  }

  public List<Map<String, Object>> aggregateDhh(
      List<Map<String, Object>> rows, List<String> groupFields) {
    Map<String, Bucket> buckets = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String key = groupFields.stream().map(field -> text(row.get(field)))
          .reduce((left, right) -> left + "\u0001" + right).orElse("");
      Bucket bucket = buckets.computeIfAbsent(key,
          ignored -> new Bucket(dimensions(row, groupFields), zeroValues(DHH_NUMERIC_FIELDS)));
      for (String field : DHH_NUMERIC_FIELDS) {
        bucket.values.put(field, number(bucket.values.get(field)) + number(row.get(field)));
      }
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Bucket bucket : buckets.values()) {
      Map<String, Object> item = new LinkedHashMap<>(bucket.dimensions);
      for (String field : DHH_NUMERIC_FIELDS) item.put(field, round(number(bucket.values.get(field)), 2));
      double spend = number(item.get("消耗"));
      double cash = number(item.get("现金消耗"));
      double commission = number(item.get("预估佣金"));
      double settled = number(item.get("结算数"));
      item.put("现金利润", round(commission - cash, 2));
      item.put("ROI", spend == 0 ? 0 : round(commission / spend, 4));
      item.put("现金ROI", cash == 0 ? 0 : round(commission / cash, 4));
      item.put("结算单价", settled == 0 ? 0 : round(commission / settled, 2));
      item.put("转化成本", number(item.get("转化数")) == 0
          ? 0 : round(spend / number(item.get("转化数")), 2));
      item.put("注册成本", number(item.get("注册数")) == 0
          ? 0 : round(spend / number(item.get("注册数")), 2));
      result.add(item);
    }
    result.sort(Comparator.comparingDouble((Map<String, Object> item) -> number(item.get("消耗"))).reversed());
    return result;
  }

  public Map<String, Object> buildDhhAlerts(
      List<Map<String, Object>> rows, String date) {
    /*
     * 账户归因口径：
     * 1. 一行有消耗账户时只关联这些账户；全部零消耗且仅一个账户时仍保留关联。
     * 2. 行级注册/结算完整计入每个相关账户，这是现有业务口径，不做账户间摊分。
     * 3. 闲鱼/咸鱼任务或账户不参与预警；阈值是消耗 >= 100 无注册，或结算严格低于注册的 90%。
     */
    List<Map<String, Object>> daily = rows.stream()
        .filter(row -> date.equals(text(row.get("日期")))).toList();
    Map<String, Map<String, Object>> combinations = new LinkedHashMap<>();
    Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
    for (Map<String, Object> row : daily) {
      String optimizer = defaultText(row.get("优化师"));
      String task = defaultText(row.get("任务名"));
      String project = text(row.get("项目"));
      if (project.isBlank()) project = CsvImportService.projectFromTask(task);
      combinations.put(String.join("\u0001", optimizer, project, task),
          mapOf("优化师", optimizer, "项目", project, "任务名", task));

      List<Map<String, Object>> listed = accountList(row.get("账户列表"));
      List<Map<String, Object>> positive = listed.stream()
          .filter(account -> number(account.get("消耗")) > 0).toList();
      List<Map<String, Object>> related = positive.isEmpty() && listed.size() == 1 ? listed : positive;
      for (Map<String, Object> account : related) {
        String id = text(account.get("账户ID"));
        String name = text(account.get("账户名称"));
        if (name.isBlank() && !id.isBlank()) name = "账户 " + id;
        if (id.isBlank() && name.isBlank()) continue;
        if (containsAny(task, "闲鱼", "咸鱼") || containsAny(name, "闲鱼", "咸鱼")) continue;
        String key = String.join("\u0001", optimizer, project, id.isBlank() ? name : id, task);
        String finalName = name;
        String finalProject = project;
        Map<String, Object> bucket = buckets.computeIfAbsent(key, ignored -> mapOf(
            "优化师", optimizer, "项目", finalProject, "账户ID", id, "账户名称", finalName,
            "任务名", task, "闲鱼任务", task.contains("闲鱼"),
            "消耗", 0d, "关联注册数", 0d, "关联结算数", 0d));
        bucket.put("消耗", number(bucket.get("消耗")) + number(account.get("消耗")));
        bucket.put("关联注册数", number(bucket.get("关联注册数")) + number(row.get("注册数")));
        bucket.put("关联结算数", number(bucket.get("关联结算数")) + number(row.get("结算数")));
      }
    }
    List<Map<String, Object>> accounts = new ArrayList<>();
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> bucket : buckets.values()) {
      Map<String, Object> account = new LinkedHashMap<>(bucket);
      for (String field : List.of("消耗", "关联注册数", "关联结算数")) {
        account.put(field, round(number(account.get(field)), 2));
      }
      accounts.add(account);
      double registrations = number(account.get("关联注册数"));
      double settlements = number(account.get("关联结算数"));
      double spend = number(account.get("消耗"));
      List<Map<String, Object>> reasons = new ArrayList<>();
      if (spend >= 100 && registrations == 0) {
        reasons.add(mapOf(
            "code", "spend_without_registration",
            "message", "账户消耗 " + decimal(spend) + " 元但关联注册数为 0"));
      }
      if (registrations > 0 && settlements < registrations * 0.9) {
        double lowerPercent = round((registrations - settlements) / registrations * 100, 2);
        reasons.add(mapOf(
            "code", "settlements_below_registrations_10pct",
            "message", "关联结算数 " + decimal(settlements) + " 比关联注册数 "
                + decimal(registrations) + " 低 " + decimal(lowerPercent) + "%"));
      }
      if (!reasons.isEmpty()) {
        Map<String, Object> item = new LinkedHashMap<>(account);
        item.put("reasons", reasons);
        items.add(item);
      }
    }
    items.sort(Comparator
        .<Map<String, Object>>comparingInt(item -> ((List<?>) item.get("reasons")).size()).reversed()
        .thenComparing(Comparator.comparingDouble(
            (Map<String, Object> item) -> number(item.get("消耗"))).reversed()));
    List<Map<String, Object>> filterOptions = new ArrayList<>(combinations.values());
    filterOptions.sort(Comparator.comparing(
        item -> text(item.get("优化师")) + "\u0001" + text(item.get("项目")) + "\u0001"
            + text(item.get("任务名")), java.text.Collator.getInstance(Locale.CHINA)));
    return mapOf(
        "date", date,
        "hasData", !daily.isEmpty(),
        "hasAccountData", daily.stream().anyMatch(row -> row.get("账户列表") instanceof List<?>),
        "accountCount", accounts.size(),
        "total", items.size(),
        "items", items,
        "filterOptions", filterOptions);
  }

  public Map<String, Object> buildJdAnalysis(
      List<Map<String, Object>> sourceRows,
      String start,
      String end,
      boolean excludeUnknownOptimizer,
      String cachedAt) {
    List<Map<String, Object>> filtered = filterJdRows(sourceRows, start, end, excludeUnknownOptimizer);
    Map<String, Object> empty = zeroValues(CsvImportService.JD_NUMERIC_FIELDS);
    List<Map<String, Object>> totals = aggregateJd(filtered, List.of());
    Map<String, Object> response = baseAnalysis(filtered, cachedAt);
    response.put("excludeUnknownOptimizer", excludeUnknownOptimizer);
    response.put("summary", totals.isEmpty() ? jdMetrics(empty) : totals.getFirst());
    response.put("by_optimizer", aggregateJd(filtered, List.of("优化师")));
    response.put("by_date", dateDescending(aggregateJd(filtered, List.of("日期"))));
    response.put("by_media", aggregateJd(filtered, List.of("媒体")));
    response.put("by_account", aggregateJd(filtered, List.of("媒体账户名称", "媒体账户ID")));
    response.put("by_promoter", aggregateJd(filtered, List.of("推客用户名")));
    response.put("by_optimizer_date", aggregateJd(filtered, List.of("日期", "优化师")));
    response.put("by_account_date",
        dateDescending(aggregateJd(filtered, List.of("日期", "媒体账户名称", "媒体账户ID"))));
    response.put("by_media_date",
        dateDescending(aggregateJd(filtered, List.of("日期", "媒体"))));
    response.put("by_promoter_date",
        dateDescending(aggregateJd(filtered, List.of("日期", "推客用户名"))));
    return response;
  }

  public List<Map<String, Object>> aggregateJd(
      List<Map<String, Object>> rows, List<String> groupFields) {
    Map<String, Bucket> buckets = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String key = groupFields.stream().map(field -> text(row.get(field)))
          .reduce((left, right) -> left + "\u0001" + right).orElse("");
      Bucket bucket = buckets.computeIfAbsent(key,
          ignored -> new Bucket(dimensions(row, groupFields),
              zeroValues(CsvImportService.JD_NUMERIC_FIELDS)));
      for (String field : CsvImportService.JD_NUMERIC_FIELDS) {
        bucket.values.put(field, number(bucket.values.get(field)) + number(row.get(field)));
      }
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Bucket bucket : buckets.values()) {
      Map<String, Object> item = new LinkedHashMap<>(bucket.dimensions);
      item.putAll(jdMetrics(bucket.values));
      result.add(item);
    }
    result.sort(Comparator.comparingDouble(
        (Map<String, Object> item) -> number(item.get("消耗"))).reversed());
    return result;
  }

  public Map<String, Object> jdMetrics(Map<String, Object> values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String field : CsvImportService.JD_NUMERIC_FIELDS) {
      result.put(field, round(number(values.get(field)), 2));
    }
    double spend = number(values.get("消耗"));
    double billable = number(values.get("计费转化数"));
    double estimated = number(values.get("首购预估佣金")) + number(values.get("回流预估佣金"));
    double actual = number(values.get("首购实际佣金")) + number(values.get("回流实际佣金"));
    double compensation = number(values.get("条件内预估赔付金额"));
    double effective = number(values.get("首购有效订单数")) + number(values.get("回流有效订单数"));
    // 预估和实际 ROI 均把条件内赔付计入收益；有效首购率以全部有效订单为分母。
    result.put("转化成本", billable == 0 ? 0 : round(spend / billable, 2));
    result.put("预估佣金合计", round(estimated, 2));
    result.put("预估利润", round(estimated + compensation - spend, 2));
    result.put("预估ROI", spend == 0 ? 0 : round((estimated + compensation) / spend, 4));
    result.put("实际佣金合计", round(actual, 2));
    result.put("实际利润", round(actual + compensation - spend, 2));
    result.put("实际ROI", spend == 0 ? 0 : round((actual + compensation) / spend, 4));
    result.put("有效订单数", round(effective, 2));
    result.put("有效首购率", effective == 0
        ? 0 : round(number(values.get("首购有效订单数")) / effective, 4));
    return result;
  }

  public List<Map<String, Object>> filterJdRows(
      List<Map<String, Object>> rows, String start, String end, boolean excludeUnknownOptimizer) {
    return rows.stream().filter(row -> inRange(row, start, end)
        && (!excludeUnknownOptimizer || !isUnknownOptimizer(row.get("优化师")))).toList();
  }

  public static boolean isUnknownOptimizer(Object value) {
    String normalized = text(value).toLowerCase(Locale.ROOT);
    return normalized.isBlank() || "-".equals(normalized)
        || Set.of("未填写", "未知", "未知优化师", "unknown").contains(normalized);
  }

  public static String beijingMonthStart(Instant now) {
    LocalDate date = now.atZone(BEIJING).toLocalDate();
    return date.withDayOfMonth(1).toString();
  }

  public static String previousBeijingDate(Instant now) {
    return now.atZone(BEIJING).toLocalDate().minusDays(1).toString();
  }

  public static String nextScheduledRefreshAt() {
    ZonedDateTime now = ZonedDateTime.now(BEIJING);
    ZonedDateTime next = now.toLocalDate().atTime(9, 0).atZone(BEIJING);
    if (!next.isAfter(now)) next = next.plusDays(1);
    return DateTimeFormatter.ISO_INSTANT.format(next.toInstant());
  }

  private Map<String, Object> baseAnalysis(
      List<Map<String, Object>> filtered, String cachedAt) {
    String minimum = "-", maximum = "-";
    if (!filtered.isEmpty()) {
      minimum = filtered.stream().map(row -> text(row.get("日期"))).min(String::compareTo).orElse("-");
      maximum = filtered.stream().map(row -> text(row.get("日期"))).max(String::compareTo).orElse("-");
    }
    return mapOf(
        "cachedAt", text(cachedAt),
        "nextScheduledRefreshAt", nextScheduledRefreshAt(),
        "rows", filtered.size(),
        "range", List.of(minimum, maximum));
  }

  private void saveSchedulerCredentials(String token, String userId) {
    try {
      Files.createDirectories(config.runtimeDir());
      Path target = config.runtimeDir().resolve("scheduler-credentials.json");
      Path temporary = config.runtimeDir().resolve("scheduler-credentials.json.tmp");
      String content = objectMapper.writeValueAsString(Map.of(
          "token", text(token),
          "userId", text(userId).isBlank() ? "20" : text(userId)));
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      setOwnerOnlyPermissions(temporary);
      try {
        Files.move(
            temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
      setOwnerOnlyPermissions(target);
    } catch (Exception error) {
      throw new IllegalStateException("保存定时更新凭据失败：" + error.getMessage(), error);
    }
  }

  private static void setOwnerOnlyPermissions(Path path) throws Exception {
    try {
      Files.setPosixFilePermissions(
          path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Windows 等非 POSIX 文件系统不支持 Unix 权限。
    }
  }

  private Map<String, String> readSchedulerCredentials() {
    try {
      Path path = config.runtimeDir().resolve("scheduler-credentials.json");
      Map<String, Object> source = objectMapper.readValue(
          Files.readString(path, StandardCharsets.UTF_8), new TypeReference<>() {});
      String token = text(source.get("token"));
      if (token.isBlank()) throw new IllegalStateException("尚未保存定时更新所需的 x-token");
      return Map.of(
          "token", token,
          "userId", text(source.get("userId")).isBlank() ? "20" : text(source.get("userId")));
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("尚未保存定时更新所需的 x-token", error);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> accountList(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().filter(Map.class::isInstance)
        .map(item -> (Map<String, Object>) item).toList();
  }

  private static boolean inRange(Map<String, Object> row, String start, String end) {
    String date = text(row.get("日期"));
    return (text(start).isBlank() || date.compareTo(start) >= 0)
        && (text(end).isBlank() || date.compareTo(end) <= 0);
  }

  private static List<Map<String, Object>> dateDescending(List<Map<String, Object>> rows) {
    List<Map<String, Object>> sorted = new ArrayList<>(rows);
    sorted.sort(Comparator.comparing(
        (Map<String, Object> item) -> text(item.get("日期"))).reversed());
    return sorted;
  }

  private static Map<String, Object> dimensions(
      Map<String, Object> row, Collection<String> fields) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (String field : fields) values.put(field, row.get(field));
    return values;
  }

  private static Map<String, Object> zeroValues(Collection<String> fields) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (String field : fields) values.put(field, 0d);
    return values;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> mapOf(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      result.put(String.valueOf(values[index]), values[index + 1]);
    }
    return result;
  }

  public static double number(Object value) {
    return CsvImportService.number(value);
  }

  public static double round(double value, int digits) {
    return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).doubleValue();
  }

  public static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String defaultText(Object value) {
    String text = text(value);
    return text.isBlank() ? "未填写" : text;
  }

  private static boolean containsAny(String value, String... keywords) {
    for (String keyword : keywords) if (value.contains(keyword)) return true;
    return false;
  }

  private static String decimal(double value) {
    if (value == Math.rint(value)) return String.valueOf((long) value);
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  private record Bucket(Map<String, Object> dimensions, Map<String, Object> values) {}
}
