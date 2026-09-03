package com.rockorca.bi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/bid-monitor")
public class BidMonitorApiController {
  private final ObjectMapper mapper;
  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

  public BidMonitorApiController(ObjectMapper mapper) { this.mapper = mapper; }

  @PostMapping("/page")
  public Map<String, Object> page(@RequestBody Map<String, Object> input) throws Exception {
    LocalDate start = LocalDate.parse(text(input, "startDate"));
    LocalDate end = LocalDate.parse(text(input, "endDate"));
    if (start.isAfter(end) || start.plusDays(92).isBefore(end))
      throw new IllegalArgumentException("查询日期范围必须为 1 至 93 天");
    int page = Integer.parseInt(text(input, "page"));
    if (page < 1 || page > 4) throw new IllegalArgumentException("仅查询消耗前 400 条，最多 4 页");
    String cookie = normalizeCookie(text(input, "cookie"));
    String user = text(input, "clientUser"), main = text(input, "mainUserId");
    if (cookie.isBlank() || !user.matches("[0-9]+") || !main.matches("[0-9]+"))
      throw new IllegalArgumentException("请填写 Cookie、client-user 和 main-user-id");
    validateCookieUser(cookie, user);
    Map<String, Object> conditions = new LinkedHashMap<>();
    conditions.put("search_field", "promotion_name");
    conditions.put("search_keyword", text(input, "keyword"));
    conditions.put("search_type", "like");
    for (String key : List.of("cl_project_id", "cl_app_id", "user_id", "media_account_id", "companys", "project_id", "scene_type", "strategy_id", "learning_phase", "external_action", "deep_external_action", "deep_bid_type", "material_id"))
      conditions.put(key, List.of());
    for (String key : List.of("landing_type", "delivery_mode", "status_first", "ad_type", "star_delivery_type", "star_task_id", "app_type", "combinatorial_id", "status", "status_second"))
      conditions.put(key, "");
    String createdStart = text(input, "createdStart"), createdEnd = text(input, "createdEnd");
    if (!createdStart.isBlank()) conditions.put("cdt_start_date", LocalDate.parse(createdStart) + " 00:00:00");
    if (!createdEnd.isBlank()) conditions.put("cdt_end_date", LocalDate.parse(createdEnd) + " 23:59:59");
    if (!createdStart.isBlank() && !createdEnd.isBlank() && createdStart.compareTo(createdEnd) > 0)
      throw new IllegalArgumentException("计划创建开始日期不能晚于结束日期");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("conditions", mapper.writeValueAsString(conditions));
    body.put("start_date", start.toString()); body.put("end_date", end.toString());
    body.put("page", page); body.put("page_size", 100);
    body.put("sort_field", "stat_cost"); body.put("sort_direction", "desc"); body.put("data_type", "list");
    if(input.get("total")!=null){long total=Long.parseLong(String.valueOf(input.get("total")));body.put("total_count",total);body.put("total_page",(total+99)/100);}
    body.put("select_kpi_fields", List.of("stat_cost", "convert_cnt", "conversion_cost", "active_register", "active_register_cost", "cpa_bid", "promotion_create_time", "account_info", "conversion_rate", "show_cnt", "cpm_platform", "click_cnt", "ctr", "cpc_platform", "active_register_rate"));
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://cli1.mobgi.com/Toutiao/Promotion/getList"))
        .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json;charset=UTF-8")
        .header("Accept", "application/json, text/plain, */*").header("Cookie", cookie)
        .header("Accept-Language", "zh-CN,zh;q=0.9")
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
        .header("Origin", "https://cl.mobgi.com").header("Referer", "https://cl.mobgi.com/")
        .header("client-user", user).header("main-user-id", main)
        .header("ff-request-id", requestId())
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new IllegalArgumentException("创量接口 HTTP " + response.statusCode() + "，请检查登录凭据或网络");
    Map<String, Object> result;
    try { result = mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {}); }
    catch (Exception error) { throw new IllegalArgumentException("创量返回的不是 JSON，请重新登录或导入报表"); }
    if (!List.of("0", "200").contains(text(result, "code")))
      throw new IllegalArgumentException(upstreamError(result, cookie));
    Object data = result.get("data");
    Map<?, ?> container = data instanceof Map<?, ?> map ? map : result;
    Object raw = data instanceof List<?> ? data : container.get("list");
    if (!(raw instanceof List<?>)) raw = container.get("rows");
    if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("创量响应缺少计划列表，请提供脱敏的成功响应以核对字段");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("计划数据格式异常");
      Map<String, Object> row = new LinkedHashMap<>();
      map.forEach((key, value) -> row.put(String.valueOf(key), value));
      rows.add(row);
    }
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("rows", rows);
    Object total = totalCount(container, result);
    output.put("total", total); output.put("page", page);
    return output;
  }

  static String requestId() {
    return ZonedDateTime.now(ReportService.BEIJING).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        + UUID.randomUUID().toString().replace("-", "") + "ff";
  }

  static Object totalCount(Map<?, ?> container, Map<?, ?> result) {
    Object total = container.get("page_info") instanceof Map<?, ?> page ? page.get("total_count") : null;
    if (total == null) total = container.get("total_count");
    if (total == null) total = result.get("total_count");
    if (total == null) total = container.get("total");
    return total;
  }

  @PostMapping("/import")
  public Map<String, Object> importExcel(@RequestParam("file") MultipartFile file) throws Exception {
    if (file.isEmpty() || file.getSize() > 20 * 1024 * 1024)
      throw new IllegalArgumentException("请选择不超过 20 MB 的 Excel 报表");
    try (var workbook = WorkbookFactory.create(file.getInputStream())) {
      var sheet = workbook.getSheetAt(0);
      var formatter = new DataFormatter();
      if (sheet.getLastRowNum() > 20000) throw new IllegalArgumentException("最多导入 20000 条计划");
      var header = sheet.getRow(sheet.getFirstRowNum());
      if (header == null) throw new IllegalArgumentException("首行必须为字段名");
      List<Map<String, Object>> rows = new ArrayList<>();
      for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
        var source = sheet.getRow(i);
        if (source == null) continue;
        Map<String, Object> row = new LinkedHashMap<>();
        for (int col = 0; col < header.getLastCellNum(); col++) {
          String key = formatter.formatCellValue(header.getCell(col)).trim();
          if (!key.isBlank()) row.put(key, formatter.formatCellValue(source.getCell(col)).trim());
        }
        if (row.values().stream().anyMatch(value -> !value.toString().isBlank())) rows.add(row);
      }
      return Map.of("rows", rows);
    }
  }

  static String normalizeCookie(String value) {
    String cookie = value.trim().replace("\\_", "_");
    if (cookie.regionMatches(true, 0, "Cookie:", 0, 7)) cookie = cookie.substring(7).trim();
    if (cookie.length() >= 2 && ((cookie.startsWith("'") && cookie.endsWith("'"))
        || (cookie.startsWith("\"") && cookie.endsWith("\"")))) cookie = cookie.substring(1, cookie.length() - 1);
    if (cookie.contains("\r") || cookie.contains("\n"))
      throw new IllegalArgumentException("Cookie 必须是单行请求头值，请不要粘贴整段 cURL");
    return cookie;
  }

  static void validateCookieUser(String cookie, String user) {
    Map<String, String> values = cookieValues(cookie);
    if (!values.containsKey("chuangliang_session") || values.get("chuangliang_session").isBlank())
      throw new IllegalArgumentException("Cookie 缺少 chuangliang_session，请复制当前成功请求的完整 Cookie");
    if (values.containsKey("userId") && !values.get("userId").equals(user))
      throw new IllegalArgumentException("Cookie 中的 userId 与 client-user 不一致，请使用同一次成功请求的凭据");
  }

  private static Map<String, String> cookieValues(String cookie) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String item : cookie.split(";")) {
      int separator = item.indexOf('=');
      if (separator > 0) values.put(item.substring(0, separator).trim(), item.substring(separator + 1).trim());
    }
    return values;
  }

  static String upstreamError(Map<String, Object> result, String cookie) {
    String detail = text(result, "message");
    if (detail.isBlank()) detail = text(result, "supplement_message");
    if (detail.isBlank()) detail = "上游未提供原因";
    String output = "创量拒绝请求（code=" + text(result, "code") + "）：" + detail
        + (text(result, "request_id").isBlank() ? "" : "；请求编号：" + text(result, "request_id"));
    for (String secret : cookieValues(cookie).values()) {
      if (!secret.isBlank()) output = output.replace(secret, "[已隐藏]");
    }
    output = output.replaceAll("[\\r\\n\\t]+", " ");
    return output.substring(0, Math.min(output.length(), 600));
  }

  private static String text(Map<String, Object> values, String key) {
    return ReportService.text(values.get(key));
  }
}
