package com.rockorca.bi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ClickflareRevenueService {
  static final String REPORT_URL =
      "https://arbi-api.hubxad.com/tm-web/api/v1/cf-campaign/list";
  private static final Duration CACHE_TTL = Duration.ofMinutes(10);
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;

  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final HttpClient client;
  private volatile Cache cache = new Cache("", Instant.EPOCH, List.of());

  public ClickflareRevenueService(RuntimeConfig config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
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
    cache = new Cache("", Instant.EPOCH, List.of());
    return credentialStatus();
  }

  public synchronized Map<String, Object> revenue(String dateValue, boolean refresh) {
    LocalDate date;
    try {
      date = LocalDate.parse(ReportService.text(dateValue));
    } catch (Exception error) {
      throw new IllegalArgumentException("收益日期格式错误", error);
    }
    Credentials credentials = credentials();
    credentials.validate();
    String cacheKey = date + "|" + credentials.companyId() + "|" + credentials.apiKeyId();
    Cache current = cache;
    if (!refresh && current.key().equals(cacheKey)
        && current.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
      return response(date, current.rows(), current.fetchedAt());
    }
    List<Map<String, Object>> rows = request(date, credentials);
    Instant fetchedAt = Instant.now();
    cache = new Cache(cacheKey, fetchedAt, rows);
    return response(date, rows, fetchedAt);
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

  private static Map<String, Object> response(
      LocalDate date, List<Map<String, Object>> rows, Instant fetchedAt) {
    return ReportService.mapOf(
        "date", date.toString(),
        "cachedAt", fetchedAt.toString(),
        "cacheMinutes", CACHE_TTL.toMinutes(),
        "rows", rows);
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
  private record Cache(String key, Instant fetchedAt, List<Map<String, Object>> rows) {}
}
