package com.rockorca.bi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class JdLowActivityUpstreamService {
  static final String REPORT_URL =
      "https://s.zaore.com/xz-cloud-api/v2/marketing/report/jd/qac";
  static final int PAGE_SIZE = 300;
  private static final int MAX_PAGES = 100;
  private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final HttpClient client;

  public JdLowActivityUpstreamService(RuntimeConfig config, ObjectMapper objectMapper) {
    this(
        config,
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  JdLowActivityUpstreamService(
      RuntimeConfig config,
      ObjectMapper objectMapper,
      HttpClient client) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.client = client;
  }

  public Map<String, Object> credentialStatus() {
    return ReportService.mapOf(
        "configured", resolvedCredentials("", "").configured(),
        "tokenSaved", !config.decodedSecret("JD_LOW_ACTIVITY_TOKEN_B64").isBlank(),
        "signSaved", !config.get("JD_LOW_ACTIVITY_SIGN", "").isBlank());
  }

  public Credentials resolvedCredentials(String tokenValue, String signValue) {
    String token = ReportService.text(tokenValue);
    String sign = ReportService.text(signValue);
    if (token.isBlank()) token = config.decodedSecret("JD_LOW_ACTIVITY_TOKEN_B64");
    if (sign.isBlank()) sign = config.get("JD_LOW_ACTIVITY_SIGN", "");
    return new Credentials(token, sign);
  }

  public List<Map<String, Object>> fetchRows(
      String startValue,
      String endValue,
      String tokenValue,
      String signValue) {
    LocalDate start = parseDate(startValue, "开始日期");
    LocalDate end = parseDate(endValue, "结束日期");
    if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
    if (ChronoUnit.DAYS.between(start, end) > 366) {
      throw new IllegalArgumentException("单次同步日期范围不能超过 367 天");
    }
    Credentials credentials = resolvedCredentials(tokenValue, signValue);
    credentials.validate();

    List<Map<String, Object>> rows = new ArrayList<>();
    int total = Integer.MAX_VALUE;
    for (int page = 1; page <= MAX_PAGES && rows.size() < total; page++) {
      Page response = requestPage(start, end, page, credentials);
      total = response.total();
      rows.addAll(response.rows());
      if (response.rows().isEmpty() || rows.size() >= total) break;
    }
    if (total == Integer.MAX_VALUE) total = rows.size();
    if (rows.size() < total) {
      throw new IllegalStateException(
          "上游分页数据不完整：应有 " + total + " 行，实际读取 " + rows.size() + " 行");
    }
    if (rows.isEmpty()) {
      throw new IllegalStateException("所选日期范围内没有京东低活明细，已取消覆盖数据库");
    }
    return rows;
  }

  private Page requestPage(
      LocalDate start,
      LocalDate end,
      int page,
      Credentials credentials) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("dates", List.of(start.toString(), end.toString()));
    payload.put("order_by", "ad_cost desc");
    payload.put("type", "ocean");
    payload.put("page_num", page);
    payload.put("page_size", PAGE_SIZE);
    try {
      String requestBody = objectMapper.writeValueAsString(payload);
      HttpRequest request = HttpRequest.newBuilder(URI.create(REPORT_URL))
          .timeout(Duration.ofSeconds(45))
          .header("Accept", "application/json, text/plain, */*")
          .header("Accept-Language", "zh-CN,zh;q=0.9")
          .header("Authorization", "Bearer " + credentials.token())
          .header("Content-Type", "application/json")
          .header("Cookie", "token=" + credentials.token())
          .header("Origin", "https://s.zaore.com")
          .header("X-Request-Sign", credentials.sign())
          .header("X-Request-Source", config.get("JD_LOW_ACTIVITY_SOURCE", "web??"))
          .header("X-Request-Timestamp", String.valueOf(Instant.now().getEpochSecond()))
          .header("User-Agent", "Mozilla/5.0")
          .POST(HttpRequest.BodyPublishers.ofString(requestBody))
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
      int total = Math.max(0, (int) ReportService.number(data.get("total")));
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Object value : objectList(data.get("list"))) {
        Map<String, Object> mapped = mapRow(objectMap(value));
        if (mapped != null) rows.add(mapped);
      }
      return new Page(total, rows);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("京东低活接口请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("京东低活接口请求失败：" + error.getMessage(), error);
    }
  }

  Map<String, Object> mapRow(Map<String, Object> source) {
    String accountId = textFor(source, "advertiser_id");
    long epoch = (long) ReportService.number(source.get("dt"));
    if (accountId.isBlank() || epoch <= 0) return null;
    if (epoch < 10_000_000_000L) epoch *= 1_000;
    String date = Instant.ofEpochMilli(epoch).atZone(BEIJING).toLocalDate().toString();
    String accountName = defaultText(textFor(source, "advertiser_name"), "账户 " + accountId);
    String explicitPlanId = textFor(
        source, "campaign_id", "plan_id", "ad_id", "promotion_id");
    String explicitPlanName = textFor(
        source, "campaign_name", "plan_name", "ad_name", "promotion_name");
    boolean independentPlan = !explicitPlanId.isBlank() || !explicitPlanName.isBlank();
    String planId = explicitPlanId.isBlank() ? accountId : explicitPlanId;
    String planName = explicitPlanName.isBlank() ? accountName : explicitPlanName;
    double spend = ReportService.number(source.get("ad_cost")) / 1_000d;

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("日期", date);
    row.put("管理员", defaultText(textFor(source, "admin_user"), "未填写"));
    row.put("任务", defaultText(textFor(source, "advertiser_user"), "京东低活"));
    row.put("账户ID", accountId);
    row.put("账户名称", accountName);
    row.put("计划ID", planId);
    row.put("计划名称", planName);
    row.put("独立计划维度", independentPlan);
    row.put("消耗", ReportService.round(spend, 4));
    row.put("金额", ReportService.number(source.get("amount")));
    row.put("展现", ReportService.number(source.get("view")));
    row.put("点击", ReportService.number(source.get("click")));
    row.put("转化数", ReportService.number(source.get("convert")));
    row.put("成功转化数", ReportService.number(source.get("cvt_success")));
    row.put("过滤转化数", ReportService.number(source.get("cvt_flt_total")));
    row.put("有效父订单数", ReportService.number(source.get("parent_order_valid")));
    row.put("有效订单UV", ReportService.number(source.get("order_valid_uv")));
    row.put("单价", ReportService.number(source.get("price")));
    row.put("有效点击UV", ReportService.number(source.get("click_valid_uv")));
    row.put("佣金", ReportService.number(source.get("reward_order_amount")));
    row.put("首日佣金", ReportService.number(source.get("first_day_reward_order_amount")));
    row.put("低佣订单数", ReportService.number(source.get("mini_reward_order_ct")));
    row.put("T3订单数", ReportService.number(source.get("t3_ct")));
    row.put("总订单数", ReportService.number(source.get("total_ct")));
    row.put("上游利润", ReportService.number(source.get("daily_profit")));
    row.put("上游模拟利润", ReportService.number(source.get("fack_porfit")));
    row.put("利润差", ReportService.number(source.get("daily_profit_gap")));
    row.put("预算毛利率", finiteNumber(source.get("budgeted_gross_margin_rate")));
    row.put("差值比例", textFor(source, "gap"));
    row.put("媒体类型", defaultText(textFor(source, "type"), "ocean"));
    row.put("联盟账户", textFor(source, "league_account"));
    row.put("客户代理", textFor(source, "customer_agent"));
    row.put("备注", textFor(source, "remark"));
    row.put("原始数据", source);
    return row;
  }

  private static LocalDate parseDate(String value, String label) {
    try {
      return LocalDate.parse(ReportService.text(value));
    } catch (Exception error) {
      throw new IllegalArgumentException(label + "格式错误", error);
    }
  }

  private static String textFor(Map<String, Object> source, String... keys) {
    for (String key : keys) {
      String value = ReportService.text(source.get(key));
      if (!value.isBlank()) return value;
    }
    return "";
  }

  private static String defaultText(String value, String fallback) {
    return value.isBlank() ? fallback : value;
  }

  private static double finiteNumber(Object value) {
    double parsed = ReportService.number(value);
    return Double.isFinite(parsed) ? parsed : 0;
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

  record Credentials(String token, String sign) {
    boolean configured() {
      return !ReportService.text(token).isBlank()
          && ReportService.text(sign).matches("(?i)^[0-9a-f]{40}$");
    }

    void validate() {
      if (!configured()) {
        throw new IllegalArgumentException("请填写接口 token 和 40 位 X-Request-Sign");
      }
    }
  }

  private record Page(int total, List<Map<String, Object>> rows) {}
}
