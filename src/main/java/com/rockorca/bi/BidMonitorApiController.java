package com.rockorca.bi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
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
    if (page < 1 || page > 200) throw new IllegalArgumentException("分页超出限制（最多 200 页）");
    String cookie = text(input, "cookie");
    String user = text(input, "clientUser"), main = text(input, "mainUserId");
    if (cookie.isBlank() || !user.matches("[0-9]+") || !main.matches("[0-9]+"))
      throw new IllegalArgumentException("请填写 Cookie、client-user 和 main-user-id");
    Map<String, Object> conditions = new LinkedHashMap<>();
    conditions.put("search_field", "promotion_name");
    conditions.put("search_keyword", text(input, "keyword"));
    conditions.put("search_type", "like");
    for (String key : List.of("cl_project_id", "cl_app_id", "user_id", "media_account_id", "companys", "project_id", "scene_type", "strategy_id", "learning_phase", "external_action", "deep_external_action", "material_id"))
      conditions.put(key, List.of());
    for (String key : List.of("landing_type", "delivery_mode", "status_first", "ad_type", "star_delivery_type", "star_task_id", "app_type", "deep_bid_type", "combinatorial_id", "status", "status_second"))
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
    body.put("select_kpi_fields", List.of("stat_cost", "convert_cnt", "conversion_cost", "active_register", "active_register_cost", "cpa_bid", "promotion_create_time", "account_info", "conversion_rate", "show_cnt", "cpm_platform", "click_cnt", "ctr", "cpc_platform", "active_register_rate"));
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://cli1.mobgi.com/Toutiao/Promotion/getList"))
        .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json;charset=UTF-8")
        .header("Accept", "application/json").header("Cookie", cookie)
        .header("Origin", "https://cl.mobgi.com").header("Referer", "https://cl.mobgi.com/")
        .header("client-user", user).header("main-user-id", main)
        .header("ff-request-id", UUID.randomUUID().toString().replace("-", ""))
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new IllegalArgumentException("创量接口 HTTP " + response.statusCode() + "，请检查登录凭据或网络");
    Map<String, Object> result;
    try { result = mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {}); }
    catch (Exception error) { throw new IllegalArgumentException("创量返回的不是 JSON，请重新登录或导入报表"); }
    if (!List.of("0", "200").contains(text(result, "code")))
      throw new IllegalArgumentException("创量拒绝请求（code=" + text(result, "code") + "）：请更新登录凭据，或使用 Excel 导入");
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
    Object total = container.get("total_count");
    if (total == null) total = result.get("total_count");
    if (total == null) total = container.get("total");
    output.put("total", total); output.put("page", page);
    return output;
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

  private static String text(Map<String, Object> values, String key) {
    return ReportService.text(values.get(key));
  }
}
