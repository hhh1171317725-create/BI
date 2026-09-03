package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/bid-monitor/snapshot")
public class BidSnapshotController {
  private final SessionService sessions;
  private final ReportRepository reports;
  private final ObjectMapper mapper;
  private final BidServerSyncStore serverSync;
  private volatile boolean initialized;
  private static final Set<String> FIELDS = Set.of("promotion_id", "promotion_name",
      "media_account_id", "media_account_name", "user_name", "stat_cost", "convert_cnt", "active_register", "cpa_bid");

  public BidSnapshotController(SessionService sessions, ReportRepository reports, ObjectMapper mapper) {
    this(sessions,reports,mapper,null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public BidSnapshotController(SessionService sessions, ReportRepository reports, ObjectMapper mapper, BidServerSyncStore serverSync) {
    this.sessions = sessions; this.reports = reports; this.mapper = mapper; this.serverSync=serverSync;
  }

  synchronized void initialize() throws Exception {
    if (initialized) return;
    try (var connection = reports.openConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS bid_monitor_snapshots (user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, payload MEDIUMTEXT NOT NULL) ENGINE=InnoDB");
    }
    initialized = true;
  }

  void write(java.sql.Connection connection, long owner, Map<String, Object> snapshot) throws Exception {
    String payload = mapper.writeValueAsString(snapshot);
    checkSize(payload);
    try (var statement = connection.prepareStatement(
        "INSERT INTO bid_monitor_snapshots(user_id,payload) VALUES (?,?) ON DUPLICATE KEY UPDATE payload=VALUES(payload)")) {
      statement.setLong(1, owner); statement.setString(2, payload); statement.executeUpdate();
    }
  }

  private long user(HttpServletRequest request) {
    var actor = sessions.currentUser(request);
    if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return actor.id();
  }

  @GetMapping
  public Map<String, Object> get(HttpServletRequest request) throws Exception {
    long owner = user(request);
    return Map.of("userId", String.valueOf(owner), "snapshot", readOwned(owner));
  }

  Map<String, Object> readOwned(long owner) throws Exception {
    initialize();
    try (var connection = reports.openConnection();
         var statement = connection.prepareStatement("SELECT payload FROM bid_monitor_snapshots WHERE user_id=?")) {
      statement.setLong(1, owner);
      try (var result = statement.executeQuery()) {
        return result.next()?mapper.readValue(result.getString(1), new TypeReference<Map<String, Object>>() {}):Map.of();
      }
    }
  }

  @GetMapping("/identity")
  public Map<String, String> identity(HttpServletRequest request) {
    return Map.of("userId", String.valueOf(user(request)));
  }

  @PostMapping
  public Map<String, Object> save(@RequestBody Map<String, Object> input, HttpServletRequest request) throws Exception {
    long owner = user(request);
    if (!String.valueOf(owner).equals(String.valueOf(input.get("expectedUserId"))))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "网站账户已切换，请重新启用同步");
    var snapshot = validate(input);
    String payload = mapper.writeValueAsString(snapshot);
    checkSize(payload);
    initialize();
    if(serverSync!=null){
      serverSync.update(owner,(connection,state)->{
        if(Boolean.TRUE.equals(state.get("enabled")))
          throw new ResponseStatusException(HttpStatus.CONFLICT,"服务器同步已启用，请停止旧插件同步");
        write(connection,owner,snapshot);
      });
    }else{
      try(var connection=reports.openConnection()){write(connection,owner,snapshot);}
    }
    return Map.of("updatedAt", snapshot.get("updatedAt"), "count", ((List<?>)snapshot.get("rows")).size());
  }

  static Map<String, Object> validate(Map<String, Object> input) {
    String date = String.valueOf(input.get("date"));
    if (!LocalDate.parse(date).equals(LocalDate.now(ReportService.BEIJING)))
      throw new IllegalArgumentException("定时同步仅保存北京时间当天数据，跨日请重新采集");
    if (!(input.get("rows") instanceof List<?> rows) || rows.isEmpty() || rows.size() > 1000000)
      throw new IllegalArgumentException("计划数据为空或超出安全范围，不覆盖旧快照");
    List<Map<String, Object>> clean = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Object item : rows) {
      if (!(item instanceof Map<?, ?> row)) throw new IllegalArgumentException("计划格式异常");
      Map<String, Object> record = new LinkedHashMap<>();
      for (String field : FIELDS) {
        Object value = row.get(field);
        if (value != null && !(value instanceof String) && !(value instanceof Number))
          throw new IllegalArgumentException("计划字段必须是文本或数字");
        if (value != null && value.toString().length() > 1000) throw new IllegalArgumentException("计划字段过长");
        record.put(field, value);
      }
      Object id = record.get("promotion_id");
      if (id == null || !id.toString().matches("[0-9]+")
          || !ids.add(record.get("media_account_id") + ":" + id))
        throw new IllegalArgumentException("计划 ID 缺失或重复");
      for (String field : List.of("stat_cost", "convert_cnt", "active_register", "cpa_bid")) {
        Object value = record.get(field);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("计划指标缺失: " + field);
        double number = Double.parseDouble(value.toString());
        if (!Double.isFinite(number) || number < 0) throw new IllegalArgumentException("计划指标无效: " + field);
      }
      clean.add(record);
    }
    Map<String, Object> snapshot = new LinkedHashMap<>(Map.of("date", date, "rows", clean, "updatedAt", Instant.now().toString()));
    if (input.containsKey("selection")) {
      boolean all="created_window_all".equals(input.get("selection"));
      boolean top400="spend_desc_top_400".equals(input.get("selection"));
      if (!all&&!top400&&!"spend_desc_top_200".equals(input.get("selection"))) throw new IllegalArgumentException("采集范围无效");
      long total=Long.parseLong(String.valueOf(input.get("upstreamTotal")));
      if (total<0 || rows.size()!=(all?total:Math.min(top400?400L:200L,total))) throw new IllegalArgumentException("计划数据不完整");
      snapshot.put("selection", input.get("selection"));snapshot.put("upstreamTotal",total);
    }
    if (input.containsKey("createdStart") || input.containsKey("createdEnd")) {
      LocalDate start = LocalDate.parse(String.valueOf(input.get("createdStart")));
      LocalDate end = LocalDate.parse(String.valueOf(input.get("createdEnd")));
      if (start.isAfter(end) || end.isAfter(LocalDate.parse(date)) || start.plusDays(89).isBefore(end))
        throw new IllegalArgumentException("计划创建范围须为 1 至 90 天，且不晚于统计日期");
      snapshot.put("createdStart", start.toString()); snapshot.put("createdEnd", end.toString());
    }
    return snapshot;
  }

  private static void checkSize(String payload){
    if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>15000000)
      throw new IllegalArgumentException("完整快照超过 15 MB 安全限制，未截断或覆盖旧数据");
  }
}
