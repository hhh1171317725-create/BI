package com.rockorca.bi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
  static final String BOARD_URL =
      "https://arbi-api.hubxad.com/tm-web/api/v1/advertiser/data/board";
  static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;

  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final HttpClient client;

  @Autowired
  public AdpfluxUpstreamService(RuntimeConfig config, ObjectMapper objectMapper) {
    this(
        config,
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  AdpfluxUpstreamService(
      RuntimeConfig config,
      ObjectMapper objectMapper,
      HttpClient client) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.client = client;
  }

  public Map<String, Object> credentialStatus() {
    Credentials credentials = resolvedCredentials("", "");
    return ReportService.mapOf(
        "configured", credentials.configured(),
        "tokenSaved", !config.decodedSecret("ADPFLUX_AUTHORIZATION_FRONT_B64").isBlank(),
        "companyIdSaved", !config.get("ADPFLUX_COMPANY_EX_ID", "").isBlank(),
        "companyId", credentials.companyId());
  }

  public Credentials resolvedCredentials(String tokenValue, String companyIdValue) {
    String token = ReportService.text(tokenValue);
    String companyId = ReportService.text(companyIdValue);
    if (token.isBlank()) token = config.decodedSecret("ADPFLUX_AUTHORIZATION_FRONT_B64");
    if (companyId.isBlank()) companyId = config.get("ADPFLUX_COMPANY_EX_ID", "");
    return new Credentials(token, companyId);
  }

  public List<Map<String, Object>> fetchRows(
      String startValue,
      String endValue,
      String tokenValue,
      String companyIdValue) {
    LocalDate start = parseDate(startValue, "开始日期");
    LocalDate end = parseDate(endValue, "结束日期");
    if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
    if (ChronoUnit.DAYS.between(start, end) > 92) {
      throw new IllegalArgumentException("单次同步日期范围不能超过 93 天");
    }
    Credentials credentials = resolvedCredentials(tokenValue, companyIdValue);
    credentials.validate();

    List<Map<String, Object>> rows = new ArrayList<>();
    for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
      rows.addAll(fetchDay(date, credentials));
    }
    if (rows.isEmpty()) {
      throw new IllegalStateException("所选日期范围内没有账户数据，已取消覆盖数据库");
    }
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
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("start_date", date.toString());
    payload.put("end_date", date.toString());
    payload.put("page", page);
    payload.put("page_size", PAGE_SIZE);
    payload.put("order_by", "total_spend");
    payload.put("order_type", "desc");
    payload.put("sealed_only", false);
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(BOARD_URL))
          .timeout(Duration.ofSeconds(45))
          .header("Accept", "*/*")
          .header("Accept-Language", "zh-CN,zh;q=0.9")
          .header("AuthorizationFront", credentials.token())
          .header("CompanyExID", credentials.companyId())
          .header("Content-Type", "application/json")
          .header("Origin", "https://www.adpflux.com")
          .header("Referer", "https://www.adpflux.com/")
          .header("User-Agent", "Mozilla/5.0")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
          .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "上游接口返回 HTTP " + response.statusCode() + "：" + shorten(response.body()));
      }
      Map<String, Object> root =
          objectMapper.readValue(response.body(), new TypeReference<>() {});
      if ((int) ReportService.number(root.get("code")) != 0) {
        throw new IllegalStateException(
            "上游接口请求失败：" + ReportService.text(root.get("message")));
      }
      Map<String, Object> data = objectMap(root.get("data"));
      Map<String, Object> pageInfo = objectMap(data.get("page_info"));
      int total = Math.max(
          (int) ReportService.number(data.get("count")),
          (int) ReportService.number(pageInfo.get("total_number")));
      int totalPages = Math.max(1, (int) ReportService.number(pageInfo.get("total_page")));
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Object value : objectList(data.get("list"))) {
        Map<String, Object> mapped = mapRow(date, objectMap(value));
        if (mapped != null) rows.add(mapped);
      }
      return new Page(total, totalPages, rows);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ADPFlux 接口请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("ADPFlux 接口请求失败：" + error.getMessage(), error);
    }
  }

  Map<String, Object> mapRow(LocalDate date, Map<String, Object> source) {
    String advertiserId = ReportService.text(source.get("advertiser_id"));
    if (advertiserId.isBlank()) return null;
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("date", date.toString());
    row.put("advertiserId", advertiserId);
    row.put("advertiserName", ReportService.text(source.get("advertiser_name")));
    row.put("balance", ReportService.number(source.get("balance")));
    row.put("billedCost", ReportService.number(source.get("billed_cost")));
    row.put("cashSpend", ReportService.number(source.get("cash_spend")));
    row.put("voucherSpend", ReportService.number(source.get("voucher_spend")));
    row.put("totalSpend", ReportService.number(source.get("total_spend")));
    row.put("clicks", ReportService.number(source.get("clicks")));
    row.put("conversions", ReportService.number(source.get("conversions")));
    row.put("cpa", ReportService.number(source.get("cpa")));
    row.put("cvr", ReportService.number(source.get("cvr")));
    row.put("currency", ReportService.text(source.get("currency")));
    row.put("status", (int) ReportService.number(source.get("status")));
    row.put("statusRaw", ReportService.text(source.get("status_raw")));
    row.put("timezone", ReportService.text(source.get("timezone")));
    row.put("closingTime", ReportService.text(source.get("closing_time")));
    row.put("raw", source);
    return row;
  }

  private static LocalDate parseDate(String value, String label) {
    try {
      return LocalDate.parse(ReportService.text(value));
    } catch (Exception error) {
      throw new IllegalArgumentException(label + "格式错误", error);
    }
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

  record Credentials(String token, String companyId) {
    boolean configured() {
      return token != null
          && token.length() >= 20
          && token.length() <= 4_000
          && token.chars().noneMatch(Character::isWhitespace)
          && companyId != null
          && companyId.matches("^[0-9]{8,30}$");
    }

    void validate() {
      if (!configured()) {
        throw new IllegalArgumentException("请填写有效的 AuthorizationFront 和 CompanyExID");
      }
    }
  }

  private record Page(int total, int totalPages, List<Map<String, Object>> rows) {}
}
