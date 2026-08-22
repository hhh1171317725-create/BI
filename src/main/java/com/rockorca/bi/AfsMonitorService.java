package com.rockorca.bi;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Synchronizes AFS metrics for every channel/style mapping used by the account mapping page. */
@Service
public class AfsMonitorService {
  static final String OVERVIEW_URL =
      "https://afs-monitor.promptpredict.top/api/admin/afs-monitor/overview";
  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final AfsMonitorRepository repository;
  private final AccountVaultRepository accountVault;
  private final HttpClient client;
  private final AtomicBoolean syncing = new AtomicBoolean(false);

  @Autowired
  public AfsMonitorService(
      RuntimeConfig config,
      ObjectMapper objectMapper,
      AfsMonitorRepository repository,
      AccountVaultRepository accountVault) {
    this(config, objectMapper, repository, accountVault,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
  }

  AfsMonitorService(
      RuntimeConfig config,
      ObjectMapper objectMapper,
      AfsMonitorRepository repository,
      AccountVaultRepository accountVault,
      HttpClient client) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.repository = repository;
    this.accountVault = accountVault;
    this.client = client;
  }

  public Map<String, Object> credentialStatus() {
    return ReportService.mapOf(
        "configured", configuredCookie(cookie()),
        "cookieSaved", configuredCookie(cookie()),
        "syncing", syncing.get());
  }

  public Map<String, Object> saveCookie(String cookieValue) {
    config.saveAfsMonitorCookie(cookieValue);
    return credentialStatus();
  }

  public Map<String, Object> query(
      String channelIdValue, String styleIdValue, String startValue, String endValue) {
    String channelId = mappingId(channelIdValue, "channel");
    String styleId = styleId(styleIdValue);
    LocalDate start = date(startValue);
    LocalDate end = date(endValue);
    if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
    if (ChronoUnit.DAYS.between(start, end) > 366) {
      throw new IllegalArgumentException("AFS查询日期范围不能超过 367 天");
    }
    List<Map<String, Object>> rows = repository.readRange(
        channelId, styleId, start.toString(), end.toString());
    return ReportService.mapOf(
        "channelId", channelId,
        "styleId", styleId,
        "start", start.toString(),
        "end", end.toString(),
        "summary", aggregate(rows),
        "byDate", rows,
        "syncing", syncing.get());
  }

  public Map<String, Object> syncToday() {
    String cookie = cookie();
    if (!configuredCookie(cookie)) throw new IllegalStateException("请先配置 AFS 监控 Cookie");
    if (!syncing.compareAndSet(false, true)) {
      throw new IllegalStateException("AFS监控数据正在同步，请稍后再试");
    }
    try {
      LocalDate today = LocalDate.now(ReportService.BEIJING);
      int count = syncMappings(today, cookie);
      return ReportService.mapOf("ok", true, "date", today.toString(), "mappings", count);
    } finally {
      syncing.set(false);
    }
  }

  /** Runs shortly after startup and then every ten minutes. */
  @Scheduled(initialDelay = 20_000, fixedDelay = 600_000)
  public void scheduledSync() {
    String cookie = cookie();
    if (!configuredCookie(cookie) || !syncing.compareAndSet(false, true)) return;
    try {
      LocalDate today = LocalDate.now(ReportService.BEIJING);
      int count = syncMappings(today, cookie);
      System.out.println("AFS监控定时更新成功：" + today + "，" + count + " 个channel/style关系");
    } catch (Exception error) {
      System.err.println("AFS监控定时更新失败：" + error.getMessage());
    } finally {
      syncing.set(false);
    }
  }

  private int syncMappings(LocalDate date, String cookie) {
    Set<Mapping> mappings = new LinkedHashSet<>();
    for (AccountVaultRepository.Entry entry : accountVault.listUsageEntries()) {
      if (!ReportService.text(entry.channelId()).isBlank()
          && !ReportService.text(entry.styleId()).isBlank()) {
        mappings.add(new Mapping(
            mappingId(entry.channelId(), "channel"), styleId(entry.styleId())));
      }
    }
    for (Mapping mapping : mappings) {
      repository.upsert(request(date, mapping.channelId(), mapping.styleId(), cookie));
    }
    return mappings.size();
  }

  Map<String, Object> mapOverview(
      String date, String channelId, String styleId, Map<String, Object> data) {
    return ReportService.mapOf(
        "date", date, "channelId", channelId, "styleId", styleId,
        "lp1PageViews", whole(data.get("lp1_page_view_count")),
        "relatedSearchLoadResults", whole(data.get("lp1_related_search_load_result_count")),
        "relatedSearchLoaded", whole(data.get("lp1_related_search_loaded_count")),
        "relatedSearchNoFill", whole(data.get("lp1_related_search_no_fill_count")),
        "relatedSearchUnknown", whole(data.get("lp1_related_search_unknown_count")),
        "relatedSearchRenderSuccess", whole(data.get("lp1_related_search_render_success_count")),
        "pageViews", whole(data.get("page_view_count")),
        "afsRenderSuccess", whole(data.get("afs_render_success_count")),
        "afsAdClicks", whole(data.get("afs_ad_click_count")));
  }

  private Map<String, Object> request(
      LocalDate date, String channelId, String styleId, String cookie) {
    String query = "?date_from=" + date + "&date_to=" + date + "&view=lp1&styleid="
        + URLEncoder.encode(styleId, StandardCharsets.UTF_8)
        + "&channel=" + URLEncoder.encode(channelId, StandardCharsets.UTF_8);
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(OVERVIEW_URL + query))
          .timeout(Duration.ofSeconds(45))
          .header("Accept", "application/json")
          .header("Cookie", cookie)
          .header("Referer", "https://afs-monitor.promptpredict.top/admin/")
          .header("User-Agent", "Mozilla/5.0")
          .GET().build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("HTTP " + response.statusCode());
      }
      Map<String, Object> root = objectMapper.readValue(
          response.body(), new TypeReference<Map<String, Object>>() {});
      if ((int) ReportService.number(root.get("code")) != 0) {
        throw new IllegalStateException(ReportService.text(root.get("message")));
      }
      return mapOverview(date.toString(), channelId, styleId, objectMap(root.get("data")));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AFS接口请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("AFS接口请求失败：" + error.getMessage(), error);
    }
  }

  static Map<String, Object> aggregate(List<Map<String, Object>> rows) {
    String[] fields = {"lp1PageViews", "relatedSearchLoadResults", "relatedSearchLoaded",
        "relatedSearchNoFill", "relatedSearchUnknown", "relatedSearchRenderSuccess",
        "pageViews", "afsRenderSuccess", "afsAdClicks"};
    Map<String, Long> totals = new LinkedHashMap<>();
    for (String field : fields) totals.put(field, 0L);
    for (Map<String, Object> row : rows) {
      for (String field : fields) totals.put(field, totals.get(field) + whole(row.get(field)));
    }
    Map<String, Object> result = new LinkedHashMap<>(totals);
    result.put("relatedSearchCallbackRate",
        ratio(totals.get("relatedSearchLoadResults"), totals.get("lp1PageViews")));
    result.put("relatedSearchFillRate",
        ratio(totals.get("relatedSearchLoaded"), totals.get("relatedSearchLoadResults")));
    result.put("relatedSearchRenderRate",
        ratio(totals.get("relatedSearchRenderSuccess"), totals.get("relatedSearchLoaded")));
    result.put("renderSuccessRate",
        ratio(totals.get("afsRenderSuccess"), totals.get("pageViews")));
    result.put("adClickRate",
        ratio(totals.get("afsAdClicks"), totals.get("afsRenderSuccess")));
    return result;
  }

  private String cookie() {
    return config.decodedSecret("AFS_MONITOR_COOKIE_B64");
  }

  private static String styleId(String value) {
    return mappingId(value, "style ID");
  }

  private static String mappingId(String value, String label) {
    String result = ReportService.text(value);
    if (result.isBlank() || result.length() > 255 || result.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(label + " 格式无效");
    }
    return result;
  }

  private static LocalDate date(String value) {
    try {
      return LocalDate.parse(ReportService.text(value));
    } catch (Exception error) {
      throw new IllegalArgumentException("AFS日期格式错误", error);
    }
  }

  private static boolean configuredCookie(String value) {
    return value != null && value.startsWith("afs_admin_session=")
        && value.length() >= 30 && value.length() <= 4_000
        && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
  }

  private static long whole(Object value) {
    return Math.round(ReportService.number(value));
  }

  private static double ratio(long numerator, long denominator) {
    return denominator == 0 ? 0 : ReportService.round((double) numerator / denominator * 100, 2);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private record Mapping(String channelId, String styleId) {}
}
