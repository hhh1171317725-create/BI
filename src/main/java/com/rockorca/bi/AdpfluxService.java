package com.rockorca.bi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
public class AdpfluxService {
  private final AdpfluxRepository repository;
  private final AdpfluxUpstreamService upstream;
  private final RuntimeConfig config;
  private final AtomicBoolean syncRunning = new AtomicBoolean(false);

  public AdpfluxService(
      AdpfluxRepository repository,
      AdpfluxUpstreamService upstream,
      RuntimeConfig config) {
    this.repository = repository;
    this.upstream = upstream;
    this.config = config;
  }

  public Map<String, Object> current() {
    LocalDate end = LocalDate.now(ReportService.BEIJING);
    return analyze(end.withDayOfMonth(1).toString(), end.toString(), "", "all", false);
  }

  public Map<String, Object> analyze(
      String startValue,
      String endValue,
      String query,
      String status,
      boolean spendingOnly) {
    LocalDate start = parseDate(startValue, "开始日期");
    LocalDate end = parseDate(endValue, "结束日期");
    if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
    if (ChronoUnit.DAYS.between(start, end) > 366) {
      throw new IllegalArgumentException("查询日期范围不能超过 367 天");
    }
    String normalizedStatus = switch (ReportService.text(status)) {
      case "enabled", "disabled" -> ReportService.text(status);
      default -> "all";
    };
    List<Map<String, Object>> rows = repository.readRows(
        start.toString(), end.toString(), query, normalizedStatus, spendingOnly);
    return buildAnalysis(rows, start.toString(), end.toString(), repository.latestSyncTime());
  }

  public Map<String, Object> sync(
      String start,
      String end,
      String token,
      String companyId) {
    return runExclusive(() -> {
      AdpfluxUpstreamService.Credentials credentials =
          upstream.resolvedCredentials(token, companyId);
      credentials.validate();
      List<Map<String, Object>> rows =
          upstream.fetchRows(start, end, credentials.token(), credentials.companyId());
      config.saveAdpfluxCredentials(credentials.token(), credentials.companyId());
      repository.replaceRange(rows, start, end, "manual");
      return analyze(start, end, "", "all", false);
    });
  }

  public Map<String, Object> credentialStatus() {
    return upstream.credentialStatus();
  }

  public Map<String, Object> saveCredentials(String token, String companyId) {
    AdpfluxUpstreamService.Credentials credentials =
        upstream.resolvedCredentials(token, companyId);
    credentials.validate();
    config.saveAdpfluxCredentials(credentials.token(), credentials.companyId());
    return upstream.credentialStatus();
  }

  @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Shanghai")
  public void scheduledSync() {
    if (!Boolean.TRUE.equals(upstream.credentialStatus().get("configured"))) return;
    try {
      runExclusive(() -> {
        String date = LocalDate.now(ReportService.BEIJING).toString();
        AdpfluxUpstreamService.Credentials credentials = upstream.resolvedCredentials("", "");
        List<Map<String, Object>> rows =
            upstream.fetchRows(date, date, credentials.token(), credentials.companyId());
        repository.replaceRange(rows, date, date, "scheduled");
        return null;
      });
      System.out.println("ADPFlux账户看板定时更新成功：" + ZonedDateTime.now(ReportService.BEIJING));
    } catch (Exception error) {
      System.err.println("ADPFlux账户看板定时更新失败：" + error.getMessage());
    }
  }

  Map<String, Object> buildAnalysis(
      List<Map<String, Object>> source,
      String start,
      String end,
      String cachedAt) {
    List<Map<String, Object>> rows = source == null ? List.of() : source;
    List<Map<String, Object>> byAccount = aggregateAccounts(rows);
    List<Map<String, Object>> byDate = aggregateDates(rows);
    double totalSpend = sum(rows, "totalSpend");
    double totalClicks = sum(rows, "clicks");
    double totalConversions = sum(rows, "conversions");
    double totalBalance = latestBalance(rows);
    long spendingAccounts = byAccount.stream()
        .filter(row -> ReportService.number(row.get("totalSpend")) > 0)
        .count();

    Map<String, Object> summary = ReportService.mapOf(
        "accounts", byAccount.size(),
        "spendingAccounts", spendingAccounts,
        "totalSpend", money(totalSpend),
        "totalBalance", money(totalBalance),
        "clicks", Math.round(totalClicks),
        "conversions", Math.round(totalConversions),
        "cpa", cost(totalSpend, totalConversions),
        "cvr", percent(totalConversions, totalClicks),
        "billedCost", money(sum(rows, "billedCost")),
        "cashSpend", money(sum(rows, "cashSpend")),
        "voucherSpend", money(sum(rows, "voucherSpend")));

    Set<String> currencies = new LinkedHashSet<>();
    for (Map<String, Object> row : rows) {
      String currency = ReportService.text(row.get("currency"));
      if (!currency.isBlank()) currencies.add(currency);
    }
    return ReportService.mapOf(
        "cachedAt", ReportService.text(cachedAt),
        "nextScheduledRefreshAt", nextScheduledRefreshAt(),
        "range", List.of(start, end),
        "rows", rows.size(),
        "currencies", currencies,
        "summary", summary,
        "by_account", byAccount,
        "by_date", byDate,
        "by_account_date", rows.stream().map(AdpfluxService::detailRow).toList());
  }

  private static List<Map<String, Object>> aggregateAccounts(List<Map<String, Object>> rows) {
    Map<String, Bucket> buckets = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String id = ReportService.text(row.get("advertiserId"));
      Bucket bucket = buckets.computeIfAbsent(id, ignored -> new Bucket(id));
      bucket.totalSpend += ReportService.number(row.get("totalSpend"));
      bucket.billedCost += ReportService.number(row.get("billedCost"));
      bucket.cashSpend += ReportService.number(row.get("cashSpend"));
      bucket.voucherSpend += ReportService.number(row.get("voucherSpend"));
      bucket.clicks += ReportService.number(row.get("clicks"));
      bucket.conversions += ReportService.number(row.get("conversions"));
      String date = ReportService.text(row.get("date"));
      if (date.compareTo(bucket.latestDate) >= 0) bucket.acceptLatest(row);
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Bucket bucket : buckets.values()) result.add(bucket.toMap());
    result.sort(Comparator.comparingDouble(
        (Map<String, Object> row) -> ReportService.number(row.get("totalSpend"))).reversed());
    return result;
  }

  private static List<Map<String, Object>> aggregateDates(List<Map<String, Object>> rows) {
    Map<String, double[]> values = new LinkedHashMap<>();
    Map<String, Set<String>> accounts = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String date = ReportService.text(row.get("date"));
      double[] bucket = values.computeIfAbsent(date, ignored -> new double[6]);
      bucket[0] += ReportService.number(row.get("totalSpend"));
      bucket[1] += ReportService.number(row.get("clicks"));
      bucket[2] += ReportService.number(row.get("conversions"));
      bucket[3] += ReportService.number(row.get("billedCost"));
      bucket[4] += ReportService.number(row.get("cashSpend"));
      bucket[5] += ReportService.number(row.get("voucherSpend"));
      accounts.computeIfAbsent(date, ignored -> new LinkedHashSet<>())
          .add(ReportService.text(row.get("advertiserId")));
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map.Entry<String, double[]> entry : values.entrySet()) {
      double[] bucket = entry.getValue();
      result.add(ReportService.mapOf(
          "date", entry.getKey(),
          "accounts", accounts.getOrDefault(entry.getKey(), Set.of()).size(),
          "totalSpend", money(bucket[0]),
          "clicks", Math.round(bucket[1]),
          "conversions", Math.round(bucket[2]),
          "cpa", cost(bucket[0], bucket[2]),
          "cvr", percent(bucket[2], bucket[1]),
          "billedCost", money(bucket[3]),
          "cashSpend", money(bucket[4]),
          "voucherSpend", money(bucket[5])));
    }
    result.sort(Comparator.comparing(
        (Map<String, Object> row) -> ReportService.text(row.get("date"))).reversed());
    return result;
  }

  private static Map<String, Object> detailRow(Map<String, Object> row) {
    Map<String, Object> detail = new LinkedHashMap<>(row);
    double spend = ReportService.number(row.get("totalSpend"));
    double clicks = ReportService.number(row.get("clicks"));
    double conversions = ReportService.number(row.get("conversions"));
    detail.put("totalSpend", money(spend));
    detail.put("balance", money(ReportService.number(row.get("balance"))));
    detail.put("billedCost", money(ReportService.number(row.get("billedCost"))));
    detail.put("cashSpend", money(ReportService.number(row.get("cashSpend"))));
    detail.put("voucherSpend", money(ReportService.number(row.get("voucherSpend"))));
    detail.put("cpa", cost(spend, conversions));
    detail.put("cvr", percent(conversions, clicks));
    return detail;
  }

  private static double latestBalance(List<Map<String, Object>> rows) {
    Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String id = ReportService.text(row.get("advertiserId"));
      Map<String, Object> previous = latest.get(id);
      if (previous == null || ReportService.text(row.get("date"))
          .compareTo(ReportService.text(previous.get("date"))) >= 0) {
        latest.put(id, row);
      }
    }
    return latest.values().stream().mapToDouble(row -> ReportService.number(row.get("balance"))).sum();
  }

  private static double sum(List<Map<String, Object>> rows, String field) {
    return rows.stream().mapToDouble(row -> ReportService.number(row.get(field))).sum();
  }

  private static double money(double value) {
    return ReportService.round(value, 2);
  }

  private static double cost(double spend, double conversions) {
    return conversions == 0 ? 0 : ReportService.round(spend / conversions, 2);
  }

  private static double percent(double numerator, double denominator) {
    return denominator == 0 ? 0 : ReportService.round(numerator / denominator * 100, 2);
  }

  private static LocalDate parseDate(String value, String label) {
    try {
      return LocalDate.parse(ReportService.text(value));
    } catch (Exception error) {
      throw new IllegalArgumentException(label + "格式错误", error);
    }
  }

  private static String nextScheduledRefreshAt() {
    ZonedDateTime now = ZonedDateTime.now(ReportService.BEIJING);
    ZonedDateTime next = now.withSecond(0).withNano(0);
    next = next.plusMinutes(10 - next.getMinute() % 10);
    return DateTimeFormatter.ISO_INSTANT.format(next.toInstant());
  }

  private <T> T runExclusive(Supplier<T> action) {
    if (!syncRunning.compareAndSet(false, true)) {
      throw new IllegalStateException("ADPFlux 账户数据正在同步，请稍后再试");
    }
    try {
      return action.get();
    } finally {
      syncRunning.set(false);
    }
  }

  private static final class Bucket {
    private final String advertiserId;
    private String advertiserName = "";
    private String latestDate = "";
    private String currency = "";
    private String timezone = "";
    private String statusRaw = "";
    private int status;
    private double balance;
    private double totalSpend;
    private double billedCost;
    private double cashSpend;
    private double voucherSpend;
    private double clicks;
    private double conversions;

    private Bucket(String advertiserId) {
      this.advertiserId = advertiserId;
    }

    private void acceptLatest(Map<String, Object> row) {
      latestDate = ReportService.text(row.get("date"));
      advertiserName = ReportService.text(row.get("advertiserName"));
      currency = ReportService.text(row.get("currency"));
      timezone = ReportService.text(row.get("timezone"));
      statusRaw = ReportService.text(row.get("statusRaw"));
      status = (int) ReportService.number(row.get("status"));
      balance = ReportService.number(row.get("balance"));
    }

    private Map<String, Object> toMap() {
      return ReportService.mapOf(
          "advertiserId", advertiserId,
          "advertiserName", advertiserName,
          "latestDate", latestDate,
          "balance", money(balance),
          "totalSpend", money(totalSpend),
          "billedCost", money(billedCost),
          "cashSpend", money(cashSpend),
          "voucherSpend", money(voucherSpend),
          "clicks", Math.round(clicks),
          "conversions", Math.round(conversions),
          "cpa", cost(totalSpend, conversions),
          "cvr", percent(conversions, clicks),
          "currency", currency,
          "timezone", timezone,
          "status", status,
          "statusRaw", statusRaw);
    }
  }
}
