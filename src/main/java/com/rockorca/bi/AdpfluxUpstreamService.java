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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdpfluxUpstreamService {
  static final String REPORT_URL =
      "https://business.tiktok.com/api/v3/bm/statistics/op/analytic/data";
  static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;
  private static final List<String> METRICS = List.of(
      "stat_cost", "cpc", "cpm", "show_cnt", "click_cnt", "ctr",
      "time_attr_convert_cnt", "time_attr_conversion_cost", "time_attr_conversion_rate",
      "convert_cnt", "conversion_cost", "conversion_rate", "show_uv", "account_type",
      "skan_convert_cnt", "skan_conversion_cost", "skan_conversion_rate", "company_name",
      "adv_contacter", "currency", "currency_precision", "is_diff_currency");

  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final HttpClient client;

  @Autowired
  public AdpfluxUpstreamService(RuntimeConfig config, ObjectMapper objectMapper) {
    this(config, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER).build());
  }

  AdpfluxUpstreamService(RuntimeConfig config, ObjectMapper objectMapper, HttpClient client) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.client = client;
  }

  public Map<String, Object> credentialStatus() {
    Credentials credentials = resolvedCredentials("", "", "", "", "", "");
    return ReportService.mapOf(
        "configured", credentials.configured(),
        "cookieSaved", !config.decodedSecret("ADPFLUX_TIKTOK_COOKIE_B64").isBlank(),
        "csrfSaved", !config.decodedSecret("ADPFLUX_CSRF_TOKEN_B64").isBlank(),
        "orgId", credentials.orgId(), "orgName", credentials.orgName(),
        "currency", credentials.currency(), "timezone", credentials.timezone());
  }

  public Credentials resolvedCredentials(
      String cookieValue, String csrfValue, String orgIdValue, String orgNameValue,
      String currencyValue, String timezoneValue) {
    String cookie = ReportService.text(cookieValue);
    String csrf = ReportService.text(csrfValue);
    String orgId = ReportService.text(orgIdValue);
    String orgName = ReportService.text(orgNameValue);
    String currency = ReportService.text(currencyValue).toUpperCase();
    String timezone = ReportService.text(timezoneValue);
    if (cookie.isBlank()) cookie = config.decodedSecret("ADPFLUX_TIKTOK_COOKIE_B64");
    if (csrf.isBlank()) csrf = config.decodedSecret("ADPFLUX_CSRF_TOKEN_B64");
    if (orgId.isBlank()) orgId = config.get("ADPFLUX_TIKTOK_ORG_ID", "");
    if (orgName.isBlank()) orgName = config.get("ADPFLUX_TIKTOK_ORG_NAME", "");
    if (currency.isBlank()) currency = config.get("ADPFLUX_TIKTOK_CURRENCY", "USD").toUpperCase();
    if (timezone.isBlank()) timezone = config.get("ADPFLUX_TIKTOK_TIMEZONE", "America/New_York");
    return new Credentials(cookie, csrf, orgId, orgName, currency, timezone);
  }

  public List<Map<String, Object>> fetchRows(
      String startValue, String endValue, Credentials credentials) {
    LocalDate start = parseDate(startValue, "开始日期");
    LocalDate end = parseDate(endValue, "结束日期");
    if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
    if (ChronoUnit.DAYS.between(start, end) > 92) {
      throw new IllegalArgumentException("单次同步日期范围不能超过 93 天");
    }
    credentials.validate();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
      rows.addAll(fetchDay(date, credentials));
    }
    if (rows.isEmpty()) throw new IllegalStateException("所选日期范围内没有账户数据，已取消覆盖数据库");
    return rows;
  }

  private List<Map<String, Object>> fetchDay(LocalDate date, Credentials credentials) {
    List<Map<String, Object>> rows = new ArrayList<>();
    int totalPages = 1;
    int expected = Integer.MAX_VALUE;
    for (int page = 1; page <= Math.min(totalPages, MAX_PAGES); page++) {
      Page response = requestPage(date, page, credentials);
      totalPages = Math.max(1, response.totalPages());
      expected = response.total();
      rows.addAll(response.rows());
      if (response.rows().isEmpty() || rows.size() >= expected) break;
    }
    if (expected != Integer.MAX_VALUE && rows.size() < expected) {
      throw new IllegalStateException(
          date + " 上游分页数据不完整：应有 " + expected + " 行，实际读取 " + rows.size() + " 行");
    }
    return rows;
  }

  private Page requestPage(LocalDate date, int page, Credentials credentials) {
    Map<String, Object> common = new LinkedHashMap<>();
    common.put("st", date.toString()); common.put("et", date.toString());
    common.put("page", page); common.put("page_size", PAGE_SIZE); common.put("metrics", METRICS);
    common.put("dimensions", List.of("advertiser_id", "advertiser_name", "adv_timezone"));
    common.put("sort_stat", "stat_cost"); common.put("sort_order", 1);
    Map<String, Object> center = ReportService.mapOf(
        "org_id", credentials.orgId(), "org_name", credentials.orgName(),
        "currency", credentials.currency(), "timezone", credentials.timezone());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("extra", ReportService.mapOf(
        "scene", "overview_table_data_reporting", "patch_control_param", "1"));
    payload.put("common_req", common);
    payload.put("adv_filter", Map.of("multilevel_bc", center));
    payload.put("operation", 1); payload.put("is_chart", 0);
    String url = REPORT_URL + "?org_id="
        + URLEncoder.encode(credentials.orgId(), StandardCharsets.UTF_8)
        + "&attr_source=&source_biz_id=&attr_type=web";
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45))
          .header("Accept", "application/json, text/plain, */*")
          .header("Accept-Language", "zh-CN,zh;q=0.9")
          .header("Content-Type", "application/json")
          .header("Cookie", credentials.cookie()).header("X-CSRFToken", credentials.csrfToken())
          .header("Origin", "https://business.tiktok.com")
          .header("Referer", "https://business.tiktok.com/").header("User-Agent", "Mozilla/5.0")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "TikTok Business 返回 HTTP " + response.statusCode() + "：" + shorten(response.body()));
      }
      Map<String, Object> root = objectMapper.readValue(response.body(), new TypeReference<>() {});
      if ((int) ReportService.number(root.get("code")) != 0) {
        throw new IllegalStateException("TikTok Business 请求失败：" + ReportService.text(root.get("msg")));
      }
      Map<String, Object> data = objectMap(root.get("data"));
      Map<String, Object> pagination = objectMap(data.get("pagination"));
      int total = (int) ReportService.number(pagination.get("total_count"));
      int pages = Math.max(1, (int) ReportService.number(pagination.get("page_count")));
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Object value : objectList(data.get("table"))) {
        Map<String, Object> mapped = mapRow(date, objectMap(value));
        if (mapped != null) rows.add(mapped);
      }
      return new Page(total, pages, rows);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("TikTok Business 请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("TikTok Business 请求失败：" + error.getMessage(), error);
    }
  }

  Map<String, Object> mapRow(LocalDate date, Map<String, Object> source) {
    String id = ReportService.text(source.get("advertiser_id"));
    if (id.isBlank()) return null;
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("date", date.toString()); row.put("advertiserId", id);
    row.put("advertiserName", ReportService.text(source.get("advertiser_name")));
    row.put("totalSpend", number(source, "stat_cost"));
    row.put("impressions", number(source, "show_cnt")); row.put("uniqueReach", number(source, "show_uv"));
    row.put("clicks", number(source, "click_cnt")); row.put("conversions", number(source, "convert_cnt"));
    row.put("cpc", number(source, "cpc")); row.put("cpm", number(source, "cpm"));
    row.put("ctr", number(source, "ctr")); row.put("cpa", number(source, "conversion_cost"));
    row.put("cvr", number(source, "conversion_rate"));
    row.put("timeAttributedConversions", number(source, "time_attr_convert_cnt"));
    row.put("timeAttributedCpa", number(source, "time_attr_conversion_cost"));
    row.put("timeAttributedCvr", number(source, "time_attr_conversion_rate"));
    row.put("skanConversions", number(source, "skan_convert_cnt"));
    row.put("skanCpa", number(source, "skan_conversion_cost")); row.put("skanCvr", number(source, "skan_conversion_rate"));
    row.put("currency", ReportService.text(source.get("currency")));
    row.put("timezone", ReportService.text(source.get("adv_timezone")));
    row.put("companyName", ReportService.text(source.get("company_name")));
    row.put("accountType", ReportService.text(source.get("account_type")));
    row.put("contact", ReportService.text(source.get("adv_contacter")));
    row.put("status", 1); row.put("statusRaw", "ACTIVE"); row.put("balance", 0);
    row.put("billedCost", 0); row.put("cashSpend", 0); row.put("voucherSpend", 0);
    row.put("closingTime", ""); row.put("raw", source);
    return row;
  }

  private static double number(Map<String, Object> source, String key) {
    return ReportService.number(source.get(key));
  }

  private static LocalDate parseDate(String value, String label) {
    try { return LocalDate.parse(ReportService.text(value)); }
    catch (Exception error) { throw new IllegalArgumentException(label + "格式错误", error); }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private static List<?> objectList(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private static String shorten(String value) {
    String text = String.valueOf(value == null ? "" : value).replaceAll("\\s+", " ").trim();
    return text.substring(0, Math.min(500, text.length()));
  }

  record Credentials(String cookie, String csrfToken, String orgId, String orgName,
                     String currency, String timezone) {
    boolean configured() {
      return cookie != null && cookie.length() >= 50 && cookie.length() <= 20_000
          && cookie.indexOf('\0') < 0 && cookie.indexOf('\r') < 0 && cookie.indexOf('\n') < 0
          && csrfToken != null && csrfToken.matches("^[A-Za-z0-9_-]{8,200}$")
          && orgId != null && orgId.matches("^[0-9]{8,30}$")
          && orgName != null && orgName.length() <= 200
          && currency != null && currency.matches("^[A-Z]{3}$")
          && timezone != null && timezone.matches("^[A-Za-z0-9_+./:-]{1,80}$");
    }
    void validate() {
      if (!configured()) throw new IllegalArgumentException("请填写有效的 TikTok Cookie、CSRF Token 和组织信息");
    }
  }

  private record Page(int total, int totalPages, List<Map<String, Object>> rows) {}
}
