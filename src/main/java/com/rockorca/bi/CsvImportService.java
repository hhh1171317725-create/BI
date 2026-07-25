package com.rockorca.bi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CsvImportService {
  private static final String DHH_EXPORT_URL =
      "https://report.rockorca.com/api/dcMarketingDhhDaily/getDcMarketingDhhDailyExport";
  private static final String JD_EXPORT_URL =
      "https://report.rockorca.com/api/marketingJdCpaDaily/getMarketingJdCpaDailyExport?dimType=detail";
  private static final List<String> DHH_NUMERIC_FIELDS =
      List.of("消耗", "现金消耗", "赠款消耗", "预估佣金", "结算数", "转化数", "注册数");
  public static final List<String> JD_NUMERIC_FIELDS = List.of(
      "转化数", "计费转化数", "去重订单总数", "首购订单总数", "回流订单总数",
      "首购有效订单数", "回流有效订单数", "首购无效订单数", "回流无效订单数",
      "首购已完成订单", "回流已完成订单", "消耗", "条件内预估赔付金额",
      "首购预估佣金", "回流预估佣金", "首购实际佣金", "回流实际佣金");

  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private final ObjectMapper objectMapper;

  public CsvImportService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public List<Map<String, Object>> fetchDhhRows(String token, String userId) {
    String raw = fetchCsv(DHH_EXPORT_URL, token, userId, "报表");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, String> record : parseCsv(raw)) {
      String date = text(record.get("日期"));
      if (date.isBlank() || "-".equals(date)) continue;
      String task = defaultText(record.get("任务名"));
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("日期", date.substring(0, Math.min(10, date.length())));
      row.put("媒体", defaultText(record.get("媒体")));
      row.put("账户列表", parseDhhAccounts(record.get("账户信息")));
      row.put("优化师", defaultText(record.get("优化师")));
      row.put("任务名", task);
      row.put("项目", projectFromTask(task));
      for (String field : DHH_NUMERIC_FIELDS) row.put(field, number(record.get(field)));
      rows.add(row);
    }
    return rows;
  }

  public List<Map<String, Object>> fetchJdRows(String token, String userId) {
    return parseJdCsv(fetchCsv(JD_EXPORT_URL, token, userId, "京东报表"));
  }

  public List<Map<String, Object>> parseJdCsv(String raw) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, String> record : parseCsv(raw)) {
      String date = text(record.get("业务日期"));
      if (date.isBlank() || "-".equals(date)) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("日期", date.substring(0, Math.min(10, date.length())));
      row.put("推广位ID", defaultText(record.get("推广位ID")));
      row.put("推广位名称", defaultText(record.get("推广位名称")));
      row.put("媒体", defaultText(record.get("媒体")));
      row.put("媒体账户ID", defaultText(record.get("媒体账户ID")));
      row.put("媒体账户名称", defaultText(record.get("媒体账户名称")));
      row.put("推客用户名", defaultText(record.get("推客用户名")));
      row.put("优化师", defaultText(record.get("优化师")));
      for (String field : JD_NUMERIC_FIELDS) row.put(field, number(record.get(field)));
      row.put("条件内预估赔付金额", number(record.get("条件内预估赔付金额(当日)")));
      rows.add(row);
    }
    return rows;
  }

  public static List<Map<String, String>> parseCsv(String text) {
    List<List<String>> records = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (quoted && character == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
        field.append('"');
        index++;
      } else if (character == '"') {
        quoted = !quoted;
      } else if (!quoted && character == ',') {
        row.add(field.toString());
        field.setLength(0);
      } else if (!quoted && (character == '\n' || character == '\r')) {
        if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
        row.add(field.toString());
        if (row.size() > 1 || !row.getFirst().isEmpty()) records.add(row);
        row = new ArrayList<>();
        field.setLength(0);
      } else {
        field.append(character);
      }
    }
    if (!field.isEmpty() || !row.isEmpty()) {
      row.add(field.toString());
      records.add(row);
    }
    if (records.isEmpty()) return List.of();
    List<String> headers = records.getFirst().stream().map(String::trim).toList();
    List<Map<String, String>> result = new ArrayList<>();
    for (int rowIndex = 1; rowIndex < records.size(); rowIndex++) {
      List<String> values = records.get(rowIndex);
      Map<String, String> item = new LinkedHashMap<>();
      for (int column = 0; column < headers.size(); column++) {
        item.put(headers.get(column), column < values.size() ? values.get(column) : "");
      }
      result.add(item);
    }
    return result;
  }

  public List<Map<String, Object>> parseDhhAccounts(Object value) {
    try {
      List<Map<String, Object>> source = objectMapper.readValue(
          String.valueOf(value == null ? "[]" : value), new TypeReference<>() {});
      List<Map<String, Object>> accounts = new ArrayList<>();
      for (Map<String, Object> account : source) {
        String id = text(account.get("media_ad_account_id"));
        String name = text(account.get("media_ad_account_name"));
        if (id.isBlank() && name.isBlank()) continue;
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("账户ID", id);
        mapped.put("账户名称", name.isBlank() ? "账户 " + id : name);
        mapped.put("消耗", number(account.get("media_total_cost")));
        mapped.put("现金消耗", number(account.get("media_cash_cost")));
        mapped.put("赠款消耗", number(account.get("media_reward_cost")));
        accounts.add(mapped);
      }
      return accounts;
    } catch (Exception ignored) {
      return List.of();
    }
  }

  public static String projectFromTask(String task) {
    if (task.contains("闲鱼")) return "淘宝闲鱼促活";
    if (task.contains("MCVR")) return "淘宝闪购MCVR";
    if (task.contains("CVR")) return "淘宝促购CVR";
    if (task.contains("UV")) return "淘宝促活UV";
    return "其他项目";
  }

  public static double number(Object value) {
    try {
      return Double.parseDouble(String.valueOf(value == null ? "0" : value).replace(",", ""));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private String fetchCsv(String url, String token, String userId, String reportName) {
    String cleanToken = text(token);
    if (cleanToken.isBlank()) throw new IllegalArgumentException("请粘贴当前 x-token");
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(60))
          .header("Accept", "application/json, text/plain, */*")
          .header("Referer", "https://report.rockorca.com/")
          .header("X-Token", cleanToken)
          .header("X-User-Id", text(userId).isBlank() ? "20" : text(userId))
          .header("Cookie", "x-token=" + cleanToken)
          .GET()
          .build();
      HttpResponse<String> response = client.send(
          request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(reportName + "接口请求失败：" + response.statusCode());
      }
      return response.body();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(reportName + "接口请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException(reportName + "接口请求失败：" + error.getMessage(), error);
    }
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String defaultText(Object value) {
    String text = text(value);
    return text.isBlank() ? "未填写" : text;
  }
}
