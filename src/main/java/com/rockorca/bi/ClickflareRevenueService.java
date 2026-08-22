package com.rockorca.bi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ClickflareRevenueService {
  static final String REPORT_URL =
      "https://arbi-api.hubxad.com/tm-web/api/v1/cf-campaign/list";
  private static final long REFRESH_MINUTES = 10;
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;

  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final ClickflareRevenueRepository repository;
  private final HttpClient client;
  private final AtomicBoolean syncRunning = new AtomicBoolean(false);

  public ClickflareRevenueService(
      RuntimeConfig config,
      ObjectMapper objectMapper,
      ClickflareRevenueRepository repository) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.repository = repository;
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  public Map<String, Object> credentialStatus() {
    Credentials credentials = credentials();
    return ReportService.mapOf(
        "configured", credentials.configured(),
        "tokenSaved", !credentials.token().isBlank(),
        "companyId", credentials.companyId(),
        "apiKeyId", credentials.apiKeyId());
  }

  public Map<String, Object> saveCredentials(
      String token,
      String companyId,
      String apiKeyId) {
    if (!ReportService.text(token).isBlank() || !ReportService.text(companyId).isBlank()) {
      config.saveAdpfluxCredentials(token, companyId);
    }
    config.saveAdpfluxCampaignApiKeyId(apiKeyId);
    return credentialStatus();
  }

  /** Reads MySQL only. Passing refresh=true performs a complete upstream sync first. */
  public Map<String, Object> revenue(String dateValue, boolean refresh) {
    LocalDate date = parseDate(dateValue);
    if (refresh) sync(date, "manual");
    return databaseResponse(date);
  }

  /** Reads and aggregates an inclusive date range from MySQL without contacting the upstream API. */
  public Map<String, Object> revenueRange(String startValue, String endValue) {
    LocalDate start = parseDate(startValue);
    LocalDate end = parseDate(endValue);
    if (start.isAfter(end)) throw new IllegalArgumentException("收益开始日期不能晚于结束日期");
    if (ChronoUnit.DAYS.between(start, end) > 366) {
      throw new IllegalArgumentException("收益查询日期范围不能超过 367 天");
    }
    List<Map<String, Object>> rows = repository.readRange(start.toString(), end.toString());
    return ReportService.mapOf(
        "start", start.toString(),
        "end", end.toString(),
        "cachedAt", repository.latestSyncTime(start.toString(), end.toString()),
        "cacheMinutes", REFRESH_MINUTES,
        "source", "mysql",
        "syncing", syncRunning.get(),
        "rows", rows);
  }

  /**
   * Runs shortly after startup and then ten minutes after each completed attempt.
   * Both today and yesterday are refreshed because ClickFlare can post delayed conversions and
   * revenue after Beijing midnight; otherwise yesterday would remain at its last pre-midnight value.
   */
  @Scheduled(initialDelay = 5_000, fixedDelay = 600_000)
  public void scheduledSync() {
    Credentials credentials = credentials();
    if (!credentials.configured() || !syncRunning.compareAndSet(false, true)) return;
    try {
      LocalDate today = LocalDate.now(ReportService.BEIJING);
      for (LocalDate date : scheduledDates(today)) {
        try {
          sync(date, "scheduled", credentials);
          System.out.println(
              "ClickFlare收益定时更新成功：" + date + " "
                  + ZonedDateTime.now(ReportService.BEIJING));
        } catch (Exception error) {
          // One date failing must not prevent the other date from being refreshed.
          System.err.println(
              "ClickFlare收益定时更新失败：" + date + " " + error.getMessage());
        }
      }
    } finally {
      syncRunning.set(false);
    }
  }

  static List<LocalDate> scheduledDates(LocalDate today) {
    return List.of(today.minusDays(1), today);
  }

  private void sync(LocalDate date, String triggerType) {
    if (!syncRunning.compareAndSet(false, true)) {
      throw new IllegalStateException("ClickFlare 收益同步正在进行，请稍后再试");
    }
    try {
      sync(date, triggerType, credentials());
    } finally {
      syncRunning.set(false);
    }
  }

  private void sync(LocalDate date, String triggerType, Credentials credentials) {
    credentials.validate();
    List<Map<String, Object>> rows = request(date, credentials);
    repository.replaceDate(date.toString(), rows, triggerType);
  }

  private Map<String, Object> databaseResponse(LocalDate date) {
    List<Map<String, Object>> rows = repository.readDate(date.toString());
    return ReportService.mapOf(
        "date", date.toString(),
        "cachedAt", repository.latestSyncTime(date.toString()),
        "cacheMinutes", REFRESH_MINUTES,
        "source", "mysql",
        "syncing", syncRunning.get(),
        "rows", rows);
  }

  private static LocalDate parseDate(String value) {
    try {
      return LocalDate.parse(ReportService.text(value));
    } catch (Exception error) {
      throw new IllegalArgumentException("收益日期格式错误", error);
    }
  }

  private Credentials credentials() {
    return new Credentials(
        config.decodedSecret("ADPFLUX_AUTHORIZATION_FRONT_B64"),
        config.get("ADPFLUX_COMPANY_EX_ID", ""),
        config.get("ADPFLUX_CF_API_KEY_ID", "26"));
  }

  private List<Map<String, Object>> request(LocalDate date, Credentials credentials) {
    List<Map<String, Object>> rows = new java.util.ArrayList<>();
    int expected = Integer.MAX_VALUE;
    for (int page = 1; page <= MAX_PAGES && rows.size() < expected; page++) {
      Page result = requestPage(date, page, credentials);
      expected = result.total();
      rows.addAll(result.rows());
      if (result.rows().isEmpty()) break;
    }
    if (expected != Integer.MAX_VALUE && rows.size() < expected) {
      throw new IllegalStateException(
          "ADPFlux 收益分页数据不完整：应有 " + expected + " 条，实际读取 " + rows.size() + " 条");
    }
    return rows;
  }

  private Page requestPage(LocalDate date, int page, Credentials credentials) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("company_id", credentials.companyId());
    payload.put("api_key_id", Integer.parseInt(credentials.apiKeyId()));
    payload.put("date_start", date.toString());
    payload.put("date_end", date.toString());
    payload.put("sort_by", "");
    payload.put("sort_order", "none");
    payload.put("page", page);
    payload.put("page_size", PAGE_SIZE);
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(REPORT_URL))
          .timeout(Duration.ofSeconds(45))
          .header("Accept", "*/*")
          .header("Content-Type", "application/json")
          .header("AuthorizationFront", credentials.token())
          .header("CompanyExID", credentials.companyId())
          .header("Origin", "https://www.adpflux.com")
          .header("Referer", "https://www.adpflux.com/")
          .header("User-Agent", "Mozilla/5.0")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "ADPFlux 收益接口返回 HTTP " + response.statusCode() + "：" + shorten(response.body()));
      }
      Map<String, Object> root = objectMapper.readValue(
          response.body(), new TypeReference<Map<String, Object>>() {});
      if ((int) ReportService.number(root.get("code")) != 0) {
        throw new IllegalStateException(
            "ADPFlux 收益接口请求失败：" + ReportService.text(root.get("message")));
      }
      Map<String, Object> data = objectMap(root.get("data"));
      List<Map<String, Object>> rows = new java.util.ArrayList<>();
      Object itemsValue = data.get("list");
      if (itemsValue instanceof List<?> items) {
        for (Object value : items) {
          if (!(value instanceof Map<?, ?> source)) continue;
          String campaignName = ReportService.text(source.get("source_name"));
          if (campaignName.isBlank()) continue;
          rows.add(ReportService.mapOf(
              "campaignId", ReportService.text(source.get("source_id")),
              "campaignName", campaignName,
              "conversions", Math.round(ReportService.number(source.get("conversions"))),
              "revenue", ReportService.round(ReportService.number(source.get("revenue")), 4),
              "spend", ReportService.round(ReportService.number(source.get("tt_spend")), 4),
              "roi", ReportService.round(ReportService.number(source.get("roi")), 2),
              "currency", ReportService.text(source.get("currency"))));
        }
      }
      return new Page(Math.max(rows.size(), (int) ReportService.number(data.get("total"))), rows);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ADPFlux 收益接口请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("ADPFlux 收益接口请求失败：" + error.getMessage(), error);
    }
  }

  private static String shorten(String value) {
    String text = ReportService.text(value).replaceAll("\\s+", " ");
    return text.length() <= 300 ? text : text.substring(0, 300) + "...";
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private record Credentials(String token, String companyId, String apiKeyId) {
    boolean configured() {
      return token.length() >= 20 && companyId.matches("^[0-9]{8,30}$")
          && apiKeyId.matches("^[0-9]{1,10}$");
    }

    void validate() {
      if (!configured()) throw new IllegalStateException("请先配置 ADPFlux 账户看板凭据");
    }
  }

  private record Page(int total, List<Map<String, Object>> rows) {}
}
