package com.rockorca.bi;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class JdLowActivityService {
  static final List<String> NUMERIC_FIELDS = List.of(
      "消耗", "金额", "展现", "点击", "转化数", "成功转化数", "过滤转化数",
      "有效父订单数", "有效订单UV", "有效点击UV", "佣金", "首日佣金",
      "低佣订单数", "T3订单数", "总订单数");

  private final ReportRepository repository;
  private final JdLowActivityUpstreamService upstream;
  private final RuntimeConfig config;
  private final AtomicBoolean syncRunning = new AtomicBoolean(false);

  public JdLowActivityService(
      ReportRepository repository,
      JdLowActivityUpstreamService upstream,
      RuntimeConfig config) {
    this.repository = repository;
    this.upstream = upstream;
    this.config = config;
  }

  public Map<String, Object> current() {
    String end = ReportService.previousBeijingDate(Instant.now());
    return analyze(end.substring(0, 8) + "01", end, "", "");
  }

  public Map<String, Object> analyze(
      String start,
      String end,
      String accountQuery,
      String task) {
    List<Map<String, Object>> rows =
        repository.readJdLowActivityRows(start, end, accountQuery, task);
    return buildAnalysis(rows, repository.latestSyncTime("jd_low_activity"));
  }

  public Map<String, Object> sync(
      String start,
      String end,
      String token,
      String sign) {
    return runExclusive(() -> {
      JdLowActivityUpstreamService.Credentials credentials =
          upstream.resolvedCredentials(token, sign);
      credentials.validate();
      List<Map<String, Object>> rows =
          upstream.fetchRows(start, end, credentials.token(), credentials.sign());
      config.saveJdLowActivityCredentials(credentials.token(), credentials.sign());
      repository.replaceJdLowActivityRange(rows, start, end, "manual");
      return analyze(start, end, "", "");
    });
  }

  public Map<String, Object> credentialStatus() {
    return upstream.credentialStatus();
  }

  @Scheduled(cron = "0 10 9 * * *", zone = "Asia/Shanghai")
  public void scheduledSync() {
    if (!Boolean.TRUE.equals(upstream.credentialStatus().get("configured"))) return;
    try {
      runExclusive(() -> {
        String end = ReportService.previousBeijingDate(Instant.now());
        String start = end.substring(0, 8) + "01";
        JdLowActivityUpstreamService.Credentials credentials =
            upstream.resolvedCredentials("", "");
        List<Map<String, Object>> rows =
            upstream.fetchRows(start, end, credentials.token(), credentials.sign());
        repository.replaceJdLowActivityRange(rows, start, end, "scheduled");
        return null;
      });
      System.out.println("京东低活定时更新成功：" + ZonedDateTime.now(ReportService.BEIJING));
    } catch (Exception error) {
      System.err.println("京东低活定时更新失败：" + error.getMessage());
    }
  }

  Map<String, Object> buildAnalysis(
      List<Map<String, Object>> rows,
      String cachedAt) {
    List<Map<String, Object>> source = rows == null ? List.of() : rows;
    List<Map<String, Object>> totals = aggregate(source, List.of());
    Map<String, Object> summary = totals.isEmpty()
        ? metrics(zeroValues(NUMERIC_FIELDS))
        : new LinkedHashMap<>(totals.getFirst());
    Set<String> accounts = new LinkedHashSet<>();
    Set<String> plans = new LinkedHashSet<>();
    Set<String> dates = new LinkedHashSet<>();
    Set<String> tasks = new LinkedHashSet<>();
    for (Map<String, Object> row : source) {
      accounts.add(ReportService.text(row.get("账户ID")));
      plans.add(ReportService.text(row.get("计划ID")));
      dates.add(ReportService.text(row.get("日期")));
      tasks.add(ReportService.text(row.get("任务")));
    }
    accounts.remove("");
    plans.remove("");
    dates.remove("");
    tasks.remove("");
    summary.put("账户数", accounts.size());
    summary.put("计划数", plans.size());
    summary.put("数据天数", dates.size());

    boolean independentPlan =
        source.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("独立计划维度")));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("cachedAt", ReportService.text(cachedAt));
    response.put("nextScheduledRefreshAt", nextScheduledRefreshAt());
    response.put("rows", source.size());
    response.put("range", range(source));
    response.put("sourcePlanAvailable", independentPlan);
    response.put("planDimensionNote", independentPlan
        ? "上游已返回独立计划字段"
        : "上游当前未返回独立计划字段，计划维度暂按账户回退展示");
    response.put("tasks", tasks.stream().sorted().toList());
    response.put("summary", summary);
    response.put("by_account", aggregate(source, List.of("账户名称", "账户ID")));
    response.put("by_date", dateDescending(aggregate(source, List.of("日期"))));
    response.put("by_plan", aggregate(
        source, List.of("计划名称", "计划ID", "独立计划维度")));
    response.put("by_task", aggregate(source, List.of("任务")));
    response.put("by_admin", aggregate(source, List.of("管理员")));
    response.put("by_account_date", dateDescending(aggregate(
        source, List.of("日期", "账户名称", "账户ID"))));
    response.put("by_plan_date", dateDescending(aggregate(
        source, List.of("日期", "计划名称", "计划ID", "账户名称", "账户ID", "独立计划维度"))));
    return response;
  }

  List<Map<String, Object>> aggregate(
      List<Map<String, Object>> rows,
      List<String> groupFields) {
    Map<String, Bucket> buckets = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String key = groupFields.stream()
          .map(field -> ReportService.text(row.get(field)))
          .reduce((left, right) -> left + "\u0001" + right)
          .orElse("");
      Bucket bucket = buckets.computeIfAbsent(
          key,
          ignored -> new Bucket(dimensions(row, groupFields), zeroValues(NUMERIC_FIELDS)));
      for (String field : NUMERIC_FIELDS) {
        bucket.values().put(
            field,
            ReportService.number(bucket.values().get(field))
                + ReportService.number(row.get(field)));
      }
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Bucket bucket : buckets.values()) {
      Map<String, Object> item = new LinkedHashMap<>(bucket.dimensions());
      item.putAll(metrics(bucket.values()));
      result.add(item);
    }
    result.sort(Comparator.comparingDouble(
        (Map<String, Object> item) -> ReportService.number(item.get("消耗"))).reversed());
    return result;
  }

  private static Map<String, Object> metrics(Map<String, Object> values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String field : NUMERIC_FIELDS) {
      result.put(field, ReportService.round(ReportService.number(values.get(field)), 2));
    }
    double spend = ReportService.number(result.get("消耗"));
    double impressions = ReportService.number(result.get("展现"));
    double clicks = ReportService.number(result.get("点击"));
    double conversions = ReportService.number(result.get("转化数"));
    double successful = ReportService.number(result.get("成功转化数"));
    double filtered = ReportService.number(result.get("过滤转化数"));
    double validOrders = ReportService.number(result.get("有效父订单数"));
    double validClicks = ReportService.number(result.get("有效点击UV"));
    double commission = ReportService.number(result.get("佣金"));
    double t3 = ReportService.number(result.get("T3订单数"));
    double totalOrders = ReportService.number(result.get("总订单数"));
    result.put("利润", ReportService.round(commission - spend, 2));
    result.put("ROI", ratio(commission, spend));
    result.put("CTR", ratio(clicks, impressions));
    result.put("CVR", ratio(conversions, clicks));
    result.put("CPC", cost(spend, clicks));
    result.put("CPM", impressions == 0
        ? 0 : ReportService.round(spend / impressions * 1_000, 2));
    result.put("转化成本", cost(spend, successful));
    result.put("有效订单成本", cost(spend, validOrders));
    result.put("有效订单率", ratio(validOrders, conversions));
    result.put("有效点击率", ratio(validClicks, clicks));
    result.put("过滤率", ratio(filtered, successful + filtered));
    result.put("T3率", ratio(t3, totalOrders));
    return result;
  }

  private static double ratio(double numerator, double denominator) {
    return denominator == 0 ? 0 : ReportService.round(numerator / denominator, 4);
  }

  private static double cost(double spend, double quantity) {
    return quantity == 0 ? 0 : ReportService.round(spend / quantity, 2);
  }

  private static Map<String, Object> zeroValues(Collection<String> fields) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (String field : fields) values.put(field, 0d);
    return values;
  }

  private static Map<String, Object> dimensions(
      Map<String, Object> row,
      Collection<String> fields) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (String field : fields) values.put(field, row.get(field));
    return values;
  }

  private static List<String> range(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return List.of("-", "-");
    String minimum = rows.stream()
        .map(row -> ReportService.text(row.get("日期")))
        .min(String::compareTo)
        .orElse("-");
    String maximum = rows.stream()
        .map(row -> ReportService.text(row.get("日期")))
        .max(String::compareTo)
        .orElse("-");
    return List.of(minimum, maximum);
  }

  private static List<Map<String, Object>> dateDescending(
      List<Map<String, Object>> rows) {
    List<Map<String, Object>> sorted = new ArrayList<>(rows);
    sorted.sort(Comparator.comparing(
        (Map<String, Object> item) -> ReportService.text(item.get("日期"))).reversed());
    return sorted;
  }

  private static String nextScheduledRefreshAt() {
    ZonedDateTime now = ZonedDateTime.now(ReportService.BEIJING);
    ZonedDateTime next = now.toLocalDate().atTime(9, 10).atZone(ReportService.BEIJING);
    if (!next.isAfter(now)) next = next.plusDays(1);
    return DateTimeFormatter.ISO_INSTANT.format(next.toInstant());
  }

  private <T> T runExclusive(Supplier<T> action) {
    if (!syncRunning.compareAndSet(false, true)) {
      throw new IllegalStateException("京东低活数据正在同步，请稍后再试");
    }
    try {
      return action.get();
    } finally {
      syncRunning.set(false);
    }
  }

  private record Bucket(
      Map<String, Object> dimensions,
      Map<String, Object> values) {}
}
