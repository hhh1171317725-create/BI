package com.rockorca.bi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CsvImportService {
  static final String DHH_EXPORT_URL =
      "https://report.rockorca.com/api/dcMarketingDhhDaily/getDcMarketingDhhDailyExport";
  static final String JD_EXPORT_URL =
      "https://report.rockorca.com/api/marketingJdCpaDaily/getMarketingJdCpaDailyExport?dimType=detail";
  private static final List<String> DHH_NUMERIC_FIELDS =
      List.of("消耗", "现金消耗", "赠款消耗", "预估佣金", "结算数", "转化数", "注册数");
  public static final List<String> JD_NUMERIC_FIELDS = List.of(
      "转化数", "计费转化数", "去重订单总数", "首购订单总数", "回流订单总数",
      "首购有效订单数", "回流有效订单数", "首购无效订单数", "回流无效订单数",
      "首购已完成订单", "回流已完成订单", "消耗", "条件内预估赔付金额",
      "首购预估佣金", "回流预估佣金", "首购实际佣金", "回流实际佣金");
  private static final Set<String> DHH_REQUIRED_HEADERS = Set.of(
      "日期", "媒体", "账户信息", "优化师", "任务名",
      "消耗", "现金消耗", "赠款消耗", "预估佣金", "结算数", "转化数", "注册数");
  private static final Set<String> JD_REQUIRED_HEADERS = Set.of(
      "业务日期", "推广位ID", "推广位名称", "媒体", "媒体账户ID", "媒体账户名称",
      "推客用户名", "优化师", "转化数", "计费转化数", "去重订单总数",
      "首购订单总数", "回流订单总数", "首购有效订单数", "回流有效订单数",
      "首购无效订单数", "回流无效订单数", "首购已完成订单", "回流已完成订单",
      "消耗", "条件内预估赔付金额(当日)", "首购预估佣金", "回流预估佣金",
      "首购实际佣金", "回流实际佣金");

  private final HttpClient client;
  private final ObjectMapper objectMapper;
  private final String dhhExportUrl;
  private final String jdExportUrl;

  @Autowired
  public CsvImportService(ObjectMapper objectMapper) {
    this(
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            // 导出请求携带 token，禁止跨地址重定向，避免凭据被转发到非预期主机。
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        DHH_EXPORT_URL,
        JD_EXPORT_URL);
  }

  CsvImportService(
      ObjectMapper objectMapper,
      HttpClient client,
      String dhhExportUrl,
      String jdExportUrl) {
    this.objectMapper = objectMapper;
    this.client = client;
    this.dhhExportUrl = dhhExportUrl;
    this.jdExportUrl = jdExportUrl;
  }

  public List<Map<String, Object>> fetchDhhRows(String token, String userId) {
    List<Map<String, Object>> rows = fetchCsvRows(
        dhhExportUrl, token, userId, "大航海报表", DHH_REQUIRED_HEADERS, this::dhhRow);
    requireDataRows(rows, "大航海报表");
    return rows;
  }

  public List<Map<String, Object>> fetchJdRows(String token, String userId) {
    List<Map<String, Object>> rows = fetchCsvRows(
        jdExportUrl, token, userId, "京东报表", JD_REQUIRED_HEADERS, this::jdRow);
    requireDataRows(rows, "京东报表");
    return rows;
  }

  public List<Map<String, Object>> parseJdCsv(String raw) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, String> record : parseCsv(raw)) {
      Map<String, Object> row = jdRow(record);
      if (row != null) rows.add(row);
    }
    return rows;
  }

  public static List<Map<String, String>> parseCsv(String text) {
    try (Reader source = new StringReader(String.valueOf(text == null ? "" : text))) {
      CsvRecordReader csv = new CsvRecordReader(source);
      List<String> headers = csv.nextRecord();
      if (headers == null) return List.of();
      headers = normalizedHeaders(headers);
      List<Map<String, String>> result = new ArrayList<>();
      List<String> values;
      while ((values = csv.nextRecord()) != null) result.add(record(headers, values));
      return result;
    } catch (IOException error) {
      throw new IllegalStateException("CSV 读取失败：" + error.getMessage(), error);
    }
  }

  /**
   * 流式 RFC 4180 状态机：一次只保留当前记录，支持 CRLF、引号内换行和双引号转义。
   */
  private static final class CsvRecordReader {
    private final Reader source;
    private int pending = -1;

    private CsvRecordReader(Reader source) {
      this.source = source;
    }

    private List<String> nextRecord() throws IOException {
      while (true) {
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean hasContent = false;
        while (true) {
          int value = pending >= 0 ? takePending() : source.read();
          if (value < 0) {
            if (quoted) throw new IllegalArgumentException("CSV 存在未闭合的引号");
            if (!hasContent && row.isEmpty() && field.isEmpty()) return null;
            row.add(field.toString());
            return row;
          }
          char character = (char) value;
          hasContent = true;
          if (quoted) {
            if (character != '"') {
              field.append(character);
              continue;
            }
            int next = source.read();
            if (next == '"') field.append('"');
            else {
              quoted = false;
              pending = next;
            }
          } else if (character == '"') {
            quoted = true;
          } else if (character == ',') {
            row.add(field.toString());
            field.setLength(0);
          } else if (character == '\n' || character == '\r') {
            if (character == '\r') {
              int next = source.read();
              if (next != '\n') pending = next;
            }
            row.add(field.toString());
            if (row.size() > 1 || !row.getFirst().isEmpty()) return row;
            break;
          } else {
            field.append(character);
          }
        }
      }
    }

    private int takePending() {
      int value = pending;
      pending = -1;
      return value;
    }
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
      double parsed = Double.parseDouble(
          String.valueOf(value == null ? "0" : value).replace(",", "").trim());
      return Double.isFinite(parsed) ? parsed : 0;
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private Map<String, Object> dhhRow(Map<String, String> record) {
    String date = normalizedDate(record.get("日期"));
    if (date.isBlank()) return null;
    String task = defaultText(record.get("任务名"));
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("日期", date);
    row.put("媒体", defaultText(record.get("媒体")));
    row.put("账户列表", parseDhhAccounts(record.get("账户信息")));
    row.put("优化师", defaultText(record.get("优化师")));
    row.put("任务名", task);
    row.put("项目", projectFromTask(task));
    for (String field : DHH_NUMERIC_FIELDS) row.put(field, number(record.get(field)));
    return row;
  }

  private Map<String, Object> jdRow(Map<String, String> record) {
    String date = normalizedDate(record.get("业务日期"));
    if (date.isBlank()) return null;
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("日期", date);
    row.put("推广位ID", defaultText(record.get("推广位ID")));
    row.put("推广位名称", defaultText(record.get("推广位名称")));
    row.put("媒体", defaultText(record.get("媒体")));
    row.put("媒体账户ID", defaultText(record.get("媒体账户ID")));
    row.put("媒体账户名称", defaultText(record.get("媒体账户名称")));
    row.put("推客用户名", defaultText(record.get("推客用户名")));
    row.put("优化师", defaultText(record.get("优化师")));
    for (String field : JD_NUMERIC_FIELDS) row.put(field, number(record.get(field)));
    row.put("条件内预估赔付金额", number(record.get("条件内预估赔付金额(当日)")));
    return row;
  }

  private List<Map<String, Object>> fetchCsvRows(
      String url,
      String token,
      String userId,
      String reportName,
      Set<String> requiredHeaders,
      Function<Map<String, String>, Map<String, Object>> mapper) {
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
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          throw new IllegalStateException(reportName + "接口请求失败：" + response.statusCode());
        }
        if (body == null) {
          throw new IllegalStateException(reportName + "接口返回空内容，请检查 token 是否有效");
        }
        return parseCsvRows(body, requiredHeaders, reportName, mapper);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(reportName + "接口请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException(reportName + "接口请求失败：" + error.getMessage(), error);
    }
  }

  private static List<Map<String, Object>> parseCsvRows(
      InputStream body,
      Set<String> requiredHeaders,
      String reportName,
      Function<Map<String, String>, Map<String, Object>> mapper) throws IOException {
    // 响应体不转成完整 String；只保留当前 CSV 记录和最终规范化底表。
    try (Reader source =
             new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8), 64 * 1024)) {
      CsvRecordReader csv = new CsvRecordReader(source);
      List<String> rawHeaders = csv.nextRecord();
      if (rawHeaders == null) {
        throw new IllegalStateException(reportName + "接口返回了空 CSV");
      }
      List<String> headers = normalizedHeaders(rawHeaders);
      String leading = headers.isEmpty() ? "" : headers.getFirst().stripLeading();
      if (leading.startsWith("<") || leading.startsWith("{") || leading.startsWith("[")) {
        throw new IllegalStateException(reportName + "接口未返回 CSV，请检查 token 是否失效");
      }
      validateCsvSchema(headers, requiredHeaders, reportName);
      List<Map<String, Object>> rows = new ArrayList<>();
      List<String> values;
      while ((values = csv.nextRecord()) != null) {
        Map<String, Object> mapped = mapper.apply(record(headers, values));
        if (mapped != null) rows.add(mapped);
      }
      return rows;
    }
  }

  private static List<String> normalizedHeaders(List<String> headers) {
    List<String> normalized = new ArrayList<>(headers.size());
    for (int index = 0; index < headers.size(); index++) {
      String header = headers.get(index).trim();
      normalized.add(index == 0 ? stripBom(header) : header);
    }
    return normalized;
  }

  private static Map<String, String> record(List<String> headers, List<String> values) {
    Map<String, String> item = new LinkedHashMap<>();
    for (int column = 0; column < headers.size(); column++) {
      item.put(headers.get(column), column < values.size() ? values.get(column) : "");
    }
    return item;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String defaultText(Object value) {
    String text = text(value);
    return text.isBlank() ? "未填写" : text;
  }

  private static void validateCsvSchema(
      List<String> headers, Set<String> requiredHeaders, String reportName) {
    Set<String> actual = new LinkedHashSet<>(headers);
    List<String> missing = requiredHeaders.stream()
        .filter(header -> !actual.contains(header))
        .sorted()
        .toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          reportName + "CSV 缺少必要列：" + String.join("、", missing));
    }
  }

  private static void requireDataRows(List<Map<String, Object>> rows, String reportName) {
    if (rows.isEmpty()) {
      throw new IllegalStateException(
          reportName + "没有可导入的有效日期行，数据库未做任何修改");
    }
  }

  private static String normalizedDate(Object value) {
    String raw = text(value);
    if (raw.isBlank() || "-".equals(raw) || raw.length() < 10) return "";
    String date = raw.substring(0, 10);
    try {
      return LocalDate.parse(date).toString();
    } catch (DateTimeParseException ignored) {
      return "";
    }
  }

  private static String stripBom(String value) {
    return !value.isEmpty() && value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
  }
}
