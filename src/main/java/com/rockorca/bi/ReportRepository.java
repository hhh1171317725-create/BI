package com.rockorca.bi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class ReportRepository {
  private static final List<String> DHH_COLUMNS = List.of(
      "business_date", "media", "optimizer", "project_name", "task_name", "account_info",
      "spend", "cash_spend", "reward_spend", "estimated_commission", "settlement_count",
      "conversion_count", "registration_count", "row_hash");
  private static final List<String> JD_COLUMNS = List.of(
      "business_date", "promotion_id", "promotion_name", "media", "media_account_id",
      "media_account_name", "promoter_username", "optimizer", "conversion_count",
      "billable_conversion_count", "deduplicated_order_count", "first_purchase_order_count",
      "return_order_count", "first_purchase_effective_orders", "return_effective_orders",
      "first_purchase_invalid_orders", "return_invalid_orders", "first_purchase_completed_orders",
      "return_completed_orders", "spend", "estimated_compensation",
      "first_purchase_estimated_commission", "return_estimated_commission",
      "first_purchase_actual_commission", "return_actual_commission", "row_hash");

  private final HikariDataSource dataSource;
  private final ObjectMapper objectMapper;
  private final ReportRowCache dhhRowsCache = new ReportRowCache();
  private final ReportRowCache jdRowsCache = new ReportRowCache();
  private final Map<String, String> syncTimeCache = new ConcurrentHashMap<>();

  public ReportRepository(RuntimeConfig config, ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    HikariConfig hikari = new HikariConfig();
    String host = config.get("MYSQL_HOST", "127.0.0.1");
    int port = config.getInt("MYSQL_PORT", 3306);
    String database = config.get("MYSQL_DATABASE", "BI");
    hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
        + "&useSSL=false&allowPublicKeyRetrieval=true");
    hikari.setUsername(config.get("MYSQL_USER", "BI"));
    hikari.setPassword(config.get("MYSQL_PASSWORD", ""));
    hikari.setMaximumPoolSize(config.getInt("MYSQL_CONNECTION_LIMIT", 5));
    hikari.setMinimumIdle(0);
    hikari.setConnectionTimeout(10_000);
    hikari.setInitializationFailTimeout(-1);
    hikari.setPoolName("marketing-reports");
    dataSource = new HikariDataSource(hikari);
  }

  public void ping() {
    try (Connection connection = dataSource.getConnection()) {
      if (!connection.isValid(5)) throw new IllegalStateException("MySQL 连接无效");
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public void replaceOne(String reportType, List<Map<String, Object>> rows, String triggerType) {
    if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("全量数据为空，已取消数据库覆盖");
    boolean dhh = switch (reportType) {
      case "dhh" -> true;
      case "jd" -> false;
      default -> throw new IllegalArgumentException("未知报表类型：" + reportType);
    };
    String table = dhh ? "dhh_daily_rows" : "jd_daily_rows";
    List<String> columns = dhh ? DHH_COLUMNS : JD_COLUMNS;
    List<List<Object>> values = uniqueValues(rows, dhh);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        // DELETE 与批量 INSERT 位于同一事务；任何插入失败都会恢复原底表。
        long runId = startRun(connection, reportType, triggerType);
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DELETE FROM `" + table + "`");
        }
        insertRows(connection, table, columns, values);
        finishRun(connection, runId, values.size());
        connection.commit();
        invalidateCache(reportType);
      } catch (Exception error) {
        connection.rollback();
        recordFailedRun(reportType, triggerType, error);
        throw error;
      }
    } catch (Exception error) {
      if (error instanceof RuntimeException runtime) throw runtime;
      throw databaseError(error);
    }
  }

  public void replaceAll(
      List<Map<String, Object>> dhhRows,
      List<Map<String, Object>> jdRows,
      String triggerType) {
    if (dhhRows == null || jdRows == null || dhhRows.isEmpty() || jdRows.isEmpty()) {
      throw new IllegalArgumentException("任一全量报表为空，已取消数据库覆盖");
    }
    List<List<Object>> dhhValues = uniqueValues(dhhRows, true);
    List<List<Object>> jdValues = uniqueValues(jdRows, false);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        // 每日调度对两张底表做原子替换，避免只更新其中一张造成口径不一致。
        long runId = startRun(connection, "all", triggerType);
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DELETE FROM `dhh_daily_rows`");
          statement.executeUpdate("DELETE FROM `jd_daily_rows`");
        }
        insertRows(connection, "dhh_daily_rows", DHH_COLUMNS, dhhValues);
        insertRows(connection, "jd_daily_rows", JD_COLUMNS, jdValues);
        finishRun(connection, runId, dhhValues.size() + jdValues.size());
        connection.commit();
        invalidateAllCaches();
      } catch (Exception error) {
        connection.rollback();
        recordFailedRun("all", triggerType, error);
        throw error;
      }
    } catch (Exception error) {
      if (error instanceof RuntimeException runtime) throw runtime;
      throw databaseError(error);
    }
  }

  public List<Map<String, Object>> readDhhRows() {
    return dhhRowsCache.get(this::queryDhhRows);
  }

  private List<Map<String, Object>> queryDhhRows() {
    String sql = """
        SELECT business_date, media, optimizer, project_name, task_name, account_info,
               spend, cash_spend, reward_spend, estimated_commission, settlement_count,
               conversion_count, registration_count
          FROM dhh_daily_rows
        """;
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet result = statement.executeQuery()) {
      while (result.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("日期", String.valueOf(result.getObject("business_date")).substring(0, 10));
        row.put("媒体", result.getString("media"));
        row.put("优化师", result.getString("optimizer"));
        row.put("项目", result.getString("project_name"));
        row.put("任务名", result.getString("task_name"));
        row.put("账户列表", parseAccountInfo(result.getString("account_info")));
        row.put("消耗", result.getDouble("spend"));
        row.put("现金消耗", result.getDouble("cash_spend"));
        row.put("赠款消耗", result.getDouble("reward_spend"));
        row.put("预估佣金", result.getDouble("estimated_commission"));
        row.put("结算数", result.getDouble("settlement_count"));
        row.put("转化数", result.getDouble("conversion_count"));
        row.put("注册数", result.getDouble("registration_count"));
        rows.add(row);
      }
      return rows;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public List<Map<String, Object>> readJdRows() {
    return jdRowsCache.get(this::queryJdRows);
  }

  private List<Map<String, Object>> queryJdRows() {
    String sql = """
        SELECT business_date, promotion_id, promotion_name, media, media_account_id,
               media_account_name, promoter_username, optimizer, conversion_count,
               billable_conversion_count, deduplicated_order_count, first_purchase_order_count,
               return_order_count, first_purchase_effective_orders, return_effective_orders,
               first_purchase_invalid_orders, return_invalid_orders, first_purchase_completed_orders,
               return_completed_orders, spend, estimated_compensation,
               first_purchase_estimated_commission, return_estimated_commission,
               first_purchase_actual_commission, return_actual_commission
          FROM jd_daily_rows
        """;
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet result = statement.executeQuery()) {
      while (result.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("日期", String.valueOf(result.getObject("business_date")).substring(0, 10));
        row.put("推广位ID", result.getString("promotion_id"));
        row.put("推广位名称", result.getString("promotion_name"));
        row.put("媒体", result.getString("media"));
        row.put("媒体账户ID", result.getString("media_account_id"));
        row.put("媒体账户名称", result.getString("media_account_name"));
        row.put("推客用户名", result.getString("promoter_username"));
        row.put("优化师", result.getString("optimizer"));
        putDouble(row, result, "转化数", "conversion_count");
        putDouble(row, result, "计费转化数", "billable_conversion_count");
        putDouble(row, result, "去重订单总数", "deduplicated_order_count");
        putDouble(row, result, "首购订单总数", "first_purchase_order_count");
        putDouble(row, result, "回流订单总数", "return_order_count");
        putDouble(row, result, "首购有效订单数", "first_purchase_effective_orders");
        putDouble(row, result, "回流有效订单数", "return_effective_orders");
        putDouble(row, result, "首购无效订单数", "first_purchase_invalid_orders");
        putDouble(row, result, "回流无效订单数", "return_invalid_orders");
        putDouble(row, result, "首购已完成订单", "first_purchase_completed_orders");
        putDouble(row, result, "回流已完成订单", "return_completed_orders");
        putDouble(row, result, "消耗", "spend");
        putDouble(row, result, "条件内预估赔付金额", "estimated_compensation");
        putDouble(row, result, "首购预估佣金", "first_purchase_estimated_commission");
        putDouble(row, result, "回流预估佣金", "return_estimated_commission");
        putDouble(row, result, "首购实际佣金", "first_purchase_actual_commission");
        putDouble(row, result, "回流实际佣金", "return_actual_commission");
        rows.add(row);
      }
      return rows;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public String latestSyncTime(String reportType) {
    return syncTimeCache.computeIfAbsent(reportType, this::queryLatestSyncTime);
  }

  private String queryLatestSyncTime(String reportType) {
    String sql = """
        SELECT DATE_FORMAT(MAX(finished_at), '%Y-%m-%dT%H:%i:%s') AS cachedAt
          FROM report_sync_runs
         WHERE status = 'success' AND report_type IN (?, 'all')
        """;
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, reportType);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getString("cachedAt") != null ? result.getString("cachedAt") : "";
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public List<Object> dhhValues(Map<String, Object> row) {
    List<Object> values = new ArrayList<>();
    for (String field : List.of("日期", "媒体", "优化师", "项目", "任务名")) {
      values.add(row.get(field));
    }
    try {
      values.add(objectMapper.writeValueAsString(
          row.get("账户列表") instanceof List<?> accounts ? accounts : List.of()));
    } catch (Exception error) {
      throw new IllegalStateException("账户信息序列化失败", error);
    }
    for (String field : List.of("消耗", "现金消耗", "赠款消耗", "预估佣金", "结算数", "转化数", "注册数")) {
      values.add(CsvImportService.number(row.get(field)));
    }
    values.add(rowHash(row));
    return values;
  }

  public List<Object> jdValues(Map<String, Object> row) {
    List<Object> values = new ArrayList<>();
    for (String field : List.of(
        "日期", "推广位ID", "推广位名称", "媒体", "媒体账户ID", "媒体账户名称", "推客用户名", "优化师",
        "转化数", "计费转化数", "去重订单总数", "首购订单总数", "回流订单总数",
        "首购有效订单数", "回流有效订单数", "首购无效订单数", "回流无效订单数",
        "首购已完成订单", "回流已完成订单", "消耗", "条件内预估赔付金额",
        "首购预估佣金", "回流预估佣金", "首购实际佣金", "回流实际佣金")) {
      values.add(row.get(field));
    }
    values.add(rowHash(row));
    return values;
  }

  public String rowHash(Map<String, Object> row) {
    try {
      byte[] json = objectMapper.writeValueAsBytes(row);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    } catch (Exception error) {
      throw new IllegalStateException("计算数据哈希失败", error);
    }
  }

  private List<List<Object>> uniqueValues(List<Map<String, Object>> rows, boolean dhh) {
    // row_hash 对规范化后的整行 JSON 做 SHA-256；重复行保留最后一次出现的值。
    Map<String, List<Object>> unique = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      List<Object> values = dhh ? dhhValues(row) : jdValues(row);
      unique.put(String.valueOf(values.getLast()), values);
    }
    return new ArrayList<>(unique.values());
  }

  private long startRun(Connection connection, String reportType, String triggerType) throws SQLException {
    String sql = """
        INSERT INTO report_sync_runs (report_type, trigger_type, status, started_at)
        VALUES (?, ?, 'running', NOW(3))
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, reportType);
      statement.setString(2, triggerType);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        if (!keys.next()) throw new SQLException("未获得同步任务 ID");
        return keys.getLong(1);
      }
    }
  }

  private void finishRun(Connection connection, long runId, int rowCount) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE report_sync_runs
           SET status = 'success', finished_at = NOW(3), row_count = ?
         WHERE id = ?
        """)) {
      statement.setInt(1, rowCount);
      statement.setLong(2, runId);
      statement.executeUpdate();
    }
  }

  private void insertRows(
      Connection connection, String table, List<String> columns, List<List<Object>> rows)
      throws SQLException {
    // table/columns 只来自本类静态白名单，不能传入用户输入。
    String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
    String sql = "INSERT INTO `" + table + "` (`" + String.join("`, `", columns)
        + "`) VALUES (" + placeholders + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int pending = 0;
      for (List<Object> row : rows) {
        for (int index = 0; index < row.size(); index++) statement.setObject(index + 1, row.get(index));
        statement.addBatch();
        if (++pending % 500 == 0) statement.executeBatch();
      }
      if (pending % 500 != 0) statement.executeBatch();
    }
  }

  private void recordFailedRun(String reportType, String triggerType, Exception error) {
    // 主事务回滚后使用独立连接记录失败，否则失败日志也会随主事务一起回滚。
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement("""
             INSERT INTO report_sync_runs
               (report_type, trigger_type, status, started_at, finished_at, row_count, error_message)
             VALUES (?, ?, 'failed', NOW(3), NOW(3), 0, ?)
             """)) {
      statement.setString(1, reportType);
      statement.setString(2, triggerType);
      String message = String.valueOf(error.getMessage());
      statement.setString(3, message.substring(0, Math.min(1000, message.length())));
      statement.executeUpdate();
    } catch (Exception ignored) {
      // 主事务错误优先返回，失败日志写入不能覆盖它。
    }
  }

  private List<Map<String, Object>> parseAccountInfo(String value) {
    try {
      return objectMapper.readValue(value == null ? "[]" : value, new TypeReference<>() {});
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static void putDouble(
      Map<String, Object> row, ResultSet result, String target, String source) throws SQLException {
    row.put(target, result.getDouble(source));
  }

  private static IllegalStateException databaseError(Exception error) {
    return new IllegalStateException(error.getMessage() == null ? "MySQL 操作失败" : error.getMessage(), error);
  }

  private void invalidateCache(String reportType) {
    if ("dhh".equals(reportType)) dhhRowsCache.invalidate();
    if ("jd".equals(reportType)) jdRowsCache.invalidate();
    syncTimeCache.remove(reportType);
  }

  private void invalidateAllCaches() {
    dhhRowsCache.invalidate();
    jdRowsCache.invalidate();
    syncTimeCache.clear();
  }

  @PreDestroy
  void close() {
    dataSource.close();
  }
}
