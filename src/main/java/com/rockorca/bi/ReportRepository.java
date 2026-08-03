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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private static final List<String> JD_LOW_ACTIVITY_COLUMNS = List.of(
      "business_date", "admin_user", "task_name", "advertiser_id", "advertiser_name",
      "plan_id", "plan_name", "has_plan_dimension", "spend", "amount", "impressions",
      "clicks", "conversions", "successful_conversions", "filtered_conversions",
      "valid_parent_orders", "valid_order_uv", "unit_price", "valid_click_uv", "commission",
      "first_day_commission", "low_commission_orders", "t3_orders", "total_orders",
      "upstream_profit", "upstream_simulated_profit", "profit_gap",
      "budgeted_gross_margin_rate", "gap_ratio", "media_type", "league_account",
      "customer_agent", "remark", "raw_json", "row_hash");

  private final HikariDataSource dataSource;
  private final ObjectMapper objectMapper;

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

  public void initializeJdLowActivitySchema() {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS `jd_low_activity_plan_rows` (
            `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
            `business_date` DATE NOT NULL,
            `admin_user` VARCHAR(255) NOT NULL DEFAULT '',
            `task_name` VARCHAR(255) NOT NULL DEFAULT '',
            `advertiser_id` VARCHAR(100) NOT NULL DEFAULT '',
            `advertiser_name` VARCHAR(500) NOT NULL DEFAULT '',
            `plan_id` VARCHAR(100) NOT NULL DEFAULT '',
            `plan_name` VARCHAR(500) NOT NULL DEFAULT '',
            `has_plan_dimension` TINYINT(1) NOT NULL DEFAULT 0,
            `spend` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `amount` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `impressions` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `clicks` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `conversions` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `successful_conversions` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `filtered_conversions` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `valid_parent_orders` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `valid_order_uv` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `unit_price` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `valid_click_uv` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `commission` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `first_day_commission` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `low_commission_orders` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `t3_orders` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `total_orders` DECIMAL(20, 2) NOT NULL DEFAULT 0,
            `upstream_profit` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `upstream_simulated_profit` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `profit_gap` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `budgeted_gross_margin_rate` DECIMAL(18, 8) NOT NULL DEFAULT 0,
            `gap_ratio` VARCHAR(50) NOT NULL DEFAULT '',
            `media_type` VARCHAR(50) NOT NULL DEFAULT '',
            `league_account` VARCHAR(255) NOT NULL DEFAULT '',
            `customer_agent` VARCHAR(255) NOT NULL DEFAULT '',
            `remark` VARCHAR(1000) NOT NULL DEFAULT '',
            `raw_json` MEDIUMTEXT NULL,
            `row_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            `synced_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3),
            PRIMARY KEY (`id`),
            UNIQUE KEY `uk_jd_low_activity_row_hash` (`row_hash`),
            KEY `idx_jd_low_activity_date` (`business_date`),
            KEY `idx_jd_low_activity_account_date` (`advertiser_id`, `business_date`),
            KEY `idx_jd_low_activity_plan_date` (`plan_id`, `business_date`),
            KEY `idx_jd_low_activity_task_date` (`task_name`, `business_date`)
          ) ENGINE=InnoDB COMMENT='京东低活任务计划维度原始明细'
          """);
      String columnType = "";
      try (PreparedStatement query = connection.prepareStatement("""
          SELECT COLUMN_TYPE
            FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'report_sync_runs'
             AND COLUMN_NAME = 'report_type'
          """);
           ResultSet result = query.executeQuery()) {
        if (result.next()) columnType = result.getString(1);
      }
      if (columnType.startsWith("enum(") && !columnType.contains("'jd_low_activity'")) {
        statement.executeUpdate("""
            ALTER TABLE `report_sync_runs`
            MODIFY COLUMN `report_type`
              ENUM('dhh', 'jd', 'jd_low_activity', 'all') NOT NULL
            """);
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  Connection openConnection() throws SQLException {
    return dataSource.getConnection();
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
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        // DELETE 与批量 INSERT 位于同一事务；任何插入失败都会恢复原底表。
        long runId = startRun(connection, reportType, triggerType);
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DELETE FROM `" + table + "`");
        }
        int inserted = insertRows(connection, table, columns, rows, dhh);
        finishRun(connection, runId, inserted);
        connection.commit();
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
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        // 每日调度对两张底表做原子替换，避免只更新其中一张造成口径不一致。
        long runId = startRun(connection, "all", triggerType);
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DELETE FROM `dhh_daily_rows`");
          statement.executeUpdate("DELETE FROM `jd_daily_rows`");
        }
        int dhhInserted = insertRows(
            connection, "dhh_daily_rows", DHH_COLUMNS, dhhRows, true);
        int jdInserted = insertRows(
            connection, "jd_daily_rows", JD_COLUMNS, jdRows, false);
        finishRun(connection, runId, dhhInserted + jdInserted);
        connection.commit();
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

  public void replaceDhhRange(
      List<Map<String, Object>> rows,
      String startValue,
      String endValue,
      String triggerType) {
    if (rows == null || rows.isEmpty()) {
      throw new IllegalArgumentException("大航海所选日期数据为空，已取消数据库覆盖");
    }
    String start = normalizedDate(startValue);
    String end = normalizedDate(endValue);
    if (start.isBlank() || end.isBlank() || start.compareTo(end) > 0) {
      throw new IllegalArgumentException("大航海同步日期范围无效");
    }
    boolean outsideRange = rows.stream()
        .map(row -> String.valueOf(row.get("日期")))
        .anyMatch(date -> date.compareTo(start) < 0 || date.compareTo(end) > 0);
    if (outsideRange) throw new IllegalArgumentException("上游返回了所选日期范围外的数据");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        long runId = startRun(connection, "dhh", triggerType);
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM `dhh_daily_rows`
             WHERE business_date BETWEEN ? AND ?
            """)) {
          statement.setString(1, start);
          statement.setString(2, end);
          statement.executeUpdate();
        }
        int inserted = insertRows(connection, "dhh_daily_rows", DHH_COLUMNS, rows, true);
        finishRun(connection, runId, inserted);
        connection.commit();
      } catch (Exception error) {
        connection.rollback();
        recordFailedRun("dhh", triggerType, error);
        throw error;
      }
    } catch (Exception error) {
      if (error instanceof RuntimeException runtime) throw runtime;
      throw databaseError(error);
    }
  }

  public void replaceDhhRangeAndJd(
      List<Map<String, Object>> dhhRows,
      String startValue,
      String endValue,
      List<Map<String, Object>> jdRows,
      String triggerType) {
    if (dhhRows == null || jdRows == null || dhhRows.isEmpty() || jdRows.isEmpty()) {
      throw new IllegalArgumentException("任一报表为空，已取消数据库更新");
    }
    String start = normalizedDate(startValue);
    String end = normalizedDate(endValue);
    if (start.isBlank() || end.isBlank() || start.compareTo(end) > 0) {
      throw new IllegalArgumentException("大航海同步日期范围无效");
    }
    boolean outsideRange = dhhRows.stream()
        .map(row -> String.valueOf(row.get("日期")))
        .anyMatch(date -> date.compareTo(start) < 0 || date.compareTo(end) > 0);
    if (outsideRange) throw new IllegalArgumentException("上游返回了所选日期范围外的数据");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        long runId = startRun(connection, "all", triggerType);
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM `dhh_daily_rows`
             WHERE business_date BETWEEN ? AND ?
            """)) {
          statement.setString(1, start);
          statement.setString(2, end);
          statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DELETE FROM `jd_daily_rows`");
        }
        int dhhInserted = insertRows(
            connection, "dhh_daily_rows", DHH_COLUMNS, dhhRows, true);
        int jdInserted = insertRows(
            connection, "jd_daily_rows", JD_COLUMNS, jdRows, false);
        finishRun(connection, runId, dhhInserted + jdInserted);
        connection.commit();
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

  public void replaceJdLowActivityRange(
      List<Map<String, Object>> rows,
      String startValue,
      String endValue,
      String triggerType) {
    if (rows == null || rows.isEmpty()) {
      throw new IllegalArgumentException("京东低活明细为空，已取消数据库覆盖");
    }
    String start = normalizedDate(startValue);
    String end = normalizedDate(endValue);
    if (start.isBlank() || end.isBlank() || start.compareTo(end) > 0) {
      throw new IllegalArgumentException("京东低活同步日期范围无效");
    }
    boolean outsideRange = rows.stream()
        .map(row -> String.valueOf(row.get("日期")))
        .anyMatch(date -> date.compareTo(start) < 0 || date.compareTo(end) > 0);
    if (outsideRange) throw new IllegalArgumentException("上游返回了所选日期范围外的数据");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        long runId = startRun(connection, "jd_low_activity", triggerType);
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM `jd_low_activity_plan_rows`
             WHERE business_date BETWEEN ? AND ?
            """)) {
          statement.setString(1, start);
          statement.setString(2, end);
          statement.executeUpdate();
        }
        int inserted = insertJdLowActivityRows(connection, rows);
        finishRun(connection, runId, inserted);
        connection.commit();
      } catch (Exception error) {
        connection.rollback();
        recordFailedRun("jd_low_activity", triggerType, error);
        throw error;
      }
    } catch (Exception error) {
      if (error instanceof RuntimeException runtime) throw runtime;
      throw databaseError(error);
    }
  }

  public List<Map<String, Object>> readDhhRows() {
    return readDhhRows("", "", "", "");
  }

  /**
   * 只从 MySQL 读取指定业务日期范围。extraDate 用于额外读取预警日期，
   * 避免为了生成昨天的预警而扫描整张大航海底表。
   */
  public List<Map<String, Object>> readDhhRows(String start, String end, String extraDate) {
    return readDhhRows(start, end, extraDate, "");
  }

  public List<Map<String, Object>> readDhhRows(
      String start, String end, String extraDate, String accountIdValue) {
    String accountId = normalizedAccountId(accountIdValue);
    RangeQuery query = rangeQuery("""
        SELECT business_date, media, optimizer, project_name, task_name, account_info,
               spend, cash_spend, reward_spend, estimated_commission, settlement_count,
               conversion_count, registration_count
          FROM dhh_daily_rows
        """, start, end, extraDate,
        accountId.isBlank() ? "" : "JSON_SEARCH(account_info, 'one', ?) IS NOT NULL",
        accountId);
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(query.sql())) {
      bindRangeParameters(statement, query.parameters());
      try (ResultSet result = statement.executeQuery()) {
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
      }
      return rows;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public List<Map<String, Object>> readJdRows() {
    return readJdRows("", "", "");
  }

  /** 只从 MySQL 读取用户选择的京东业务日期范围。 */
  public List<Map<String, Object>> readJdRows(String start, String end) {
    return readJdRows(start, end, "");
  }

  public List<Map<String, Object>> readJdRows(String start, String end, String accountIdValue) {
    String accountId = normalizedAccountId(accountIdValue);
    RangeQuery query = rangeQuery("""
        SELECT business_date, promotion_id, promotion_name, media, media_account_id,
               media_account_name, promoter_username, optimizer, conversion_count,
               billable_conversion_count, deduplicated_order_count, first_purchase_order_count,
               return_order_count, first_purchase_effective_orders, return_effective_orders,
               first_purchase_invalid_orders, return_invalid_orders, first_purchase_completed_orders,
               return_completed_orders, spend, estimated_compensation,
               first_purchase_estimated_commission, return_estimated_commission,
               first_purchase_actual_commission, return_actual_commission
          FROM jd_daily_rows
        """, start, end, "",
        accountId.isBlank() ? "" : "media_account_id = ?", accountId);
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(query.sql())) {
      bindRangeParameters(statement, query.parameters());
      try (ResultSet result = statement.executeQuery()) {
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
      }
      return rows;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public List<Map<String, Object>> readJdLowActivityRows(
      String startValue,
      String endValue,
      String accountQueryValue,
      String taskValue) {
    String start = normalizedDate(startValue);
    String end = normalizedDate(endValue);
    if (!start.isBlank() && !end.isBlank() && start.compareTo(end) > 0) {
      throw new IllegalArgumentException("开始日期不能晚于结束日期");
    }
    String accountQuery = normalizedSearch(accountQueryValue, "账户搜索");
    String task = normalizedSearch(taskValue, "任务筛选");
    List<String> conditions = new ArrayList<>();
    List<String> parameters = new ArrayList<>();
    if (!start.isBlank()) {
      conditions.add("business_date >= ?");
      parameters.add(start);
    }
    if (!end.isBlank()) {
      conditions.add("business_date <= ?");
      parameters.add(end);
    }
    if (!accountQuery.isBlank()) {
      conditions.add("(advertiser_id = ? OR advertiser_name LIKE ?)");
      parameters.add(accountQuery);
      parameters.add("%" + escapeLike(accountQuery) + "%");
    }
    if (!task.isBlank()) {
      conditions.add("task_name = ?");
      parameters.add(task);
    }
    StringBuilder sql = new StringBuilder("""
        SELECT business_date, admin_user, task_name, advertiser_id, advertiser_name,
               plan_id, plan_name, has_plan_dimension, spend, amount, impressions, clicks,
               conversions, successful_conversions, filtered_conversions,
               valid_parent_orders, valid_order_uv, unit_price, valid_click_uv, commission,
               first_day_commission, low_commission_orders, t3_orders, total_orders,
               upstream_profit, upstream_simulated_profit, profit_gap,
               budgeted_gross_margin_rate, gap_ratio, media_type, league_account,
               customer_agent, remark
          FROM jd_low_activity_plan_rows
        """);
    if (!conditions.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", conditions));
    sql.append(" ORDER BY business_date DESC, spend DESC");

    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      bindRangeParameters(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("日期", String.valueOf(result.getObject("business_date")).substring(0, 10));
          row.put("管理员", result.getString("admin_user"));
          row.put("任务", result.getString("task_name"));
          row.put("账户ID", result.getString("advertiser_id"));
          row.put("账户名称", result.getString("advertiser_name"));
          row.put("计划ID", result.getString("plan_id"));
          row.put("计划名称", result.getString("plan_name"));
          row.put("独立计划维度", result.getBoolean("has_plan_dimension"));
          putDouble(row, result, "消耗", "spend");
          putDouble(row, result, "金额", "amount");
          putDouble(row, result, "展现", "impressions");
          putDouble(row, result, "点击", "clicks");
          putDouble(row, result, "转化数", "conversions");
          putDouble(row, result, "成功转化数", "successful_conversions");
          putDouble(row, result, "过滤转化数", "filtered_conversions");
          putDouble(row, result, "有效父订单数", "valid_parent_orders");
          putDouble(row, result, "有效订单UV", "valid_order_uv");
          putDouble(row, result, "单价", "unit_price");
          putDouble(row, result, "有效点击UV", "valid_click_uv");
          putDouble(row, result, "佣金", "commission");
          putDouble(row, result, "首日佣金", "first_day_commission");
          putDouble(row, result, "低佣订单数", "low_commission_orders");
          putDouble(row, result, "T3订单数", "t3_orders");
          putDouble(row, result, "总订单数", "total_orders");
          putDouble(row, result, "上游利润", "upstream_profit");
          putDouble(row, result, "上游模拟利润", "upstream_simulated_profit");
          putDouble(row, result, "利润差", "profit_gap");
          putDouble(row, result, "预算毛利率", "budgeted_gross_margin_rate");
          row.put("差值比例", result.getString("gap_ratio"));
          row.put("媒体类型", result.getString("media_type"));
          row.put("联盟账户", result.getString("league_account"));
          row.put("客户代理", result.getString("customer_agent"));
          row.put("备注", result.getString("remark"));
          rows.add(row);
        }
      }
      return rows;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private static RangeQuery rangeQuery(
      String selectSql,
      String startValue,
      String endValue,
      String extraDateValue,
      String filterCondition,
      String filterValue) {
    String start = normalizedDate(startValue);
    String end = normalizedDate(endValue);
    String extraDate = normalizedDate(extraDateValue);
    if (!start.isBlank() && !end.isBlank() && start.compareTo(end) > 0) {
      throw new IllegalArgumentException("开始日期不能晚于结束日期");
    }

    List<String> rangeConditions = new ArrayList<>();
    List<String> parameters = new ArrayList<>();
    if (!start.isBlank()) {
      rangeConditions.add("business_date >= ?");
      parameters.add(start);
    }
    if (!end.isBlank()) {
      rangeConditions.add("business_date <= ?");
      parameters.add(end);
    }

    StringBuilder sql = new StringBuilder(selectSql);
    List<String> whereConditions = new ArrayList<>();
    if (!rangeConditions.isEmpty()) {
      whereConditions.add("(" + String.join(" AND ", rangeConditions) + ")");
    }
    if (!extraDate.isBlank()) {
      if (rangeConditions.isEmpty()) {
        whereConditions.add("business_date = ?");
      } else {
        String dateCondition = whereConditions.removeLast();
        whereConditions.add("(" + dateCondition + " OR business_date = ?)");
      }
      parameters.add(extraDate);
    }
    if (filterCondition != null && !filterCondition.isBlank()) {
      whereConditions.add(filterCondition);
      parameters.add(filterValue);
    }
    if (!whereConditions.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", whereConditions));
    }
    sql.append(" ORDER BY business_date ASC");
    return new RangeQuery(sql.toString(), parameters);
  }

  private static String normalizedDate(String value) {
    String date = value == null ? "" : value.trim();
    if (date.isBlank() || "-".equals(date)) {
      return "";
    }
    try {
      return LocalDate.parse(date).toString();
    } catch (DateTimeParseException error) {
      throw new IllegalArgumentException("日期格式错误：" + date, error);
    }
  }

  private static String normalizedAccountId(String value) {
    String accountId = value == null ? "" : value.trim();
    if (accountId.isBlank()) {
      return "";
    }
    if (accountId.length() > 100 || !accountId.matches("[0-9]+")) {
      throw new IllegalArgumentException("账户 ID 只能包含数字，且长度不能超过 100 位");
    }
    return accountId;
  }

  private static String normalizedSearch(String value, String label) {
    String search = value == null ? "" : value.trim();
    if (search.length() > 100 || search.indexOf('\0') >= 0
        || search.indexOf('\r') >= 0 || search.indexOf('\n') >= 0) {
      throw new IllegalArgumentException(label + "格式无效");
    }
    return search;
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static void bindRangeParameters(
      PreparedStatement statement, List<String> parameters) throws SQLException {
    for (int index = 0; index < parameters.size(); index++) {
      statement.setString(index + 1, parameters.get(index));
    }
  }

  private record RangeQuery(String sql, List<String> parameters) {}

  public String latestSyncTime(String reportType) {
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

  public List<Object> jdLowActivityValues(Map<String, Object> row) {
    List<Object> values = new ArrayList<>();
    for (String field : List.of(
        "日期", "管理员", "任务", "账户ID", "账户名称", "计划ID", "计划名称")) {
      values.add(row.get(field));
    }
    values.add(Boolean.TRUE.equals(row.get("独立计划维度")) ? 1 : 0);
    for (String field : List.of(
        "消耗", "金额", "展现", "点击", "转化数", "成功转化数", "过滤转化数",
        "有效父订单数", "有效订单UV", "单价", "有效点击UV", "佣金", "首日佣金",
        "低佣订单数", "T3订单数", "总订单数", "上游利润", "上游模拟利润",
        "利润差", "预算毛利率")) {
      values.add(CsvImportService.number(row.get(field)));
    }
    for (String field : List.of(
        "差值比例", "媒体类型", "联盟账户", "客户代理", "备注")) {
      values.add(row.get(field));
    }
    try {
      values.add(objectMapper.writeValueAsString(
          row.get("原始数据") instanceof Map<?, ?> raw ? raw : Map.of()));
    } catch (Exception error) {
      throw new IllegalStateException("京东低活原始数据序列化失败", error);
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

  private int insertRows(
      Connection connection,
      String table,
      List<String> columns,
      List<Map<String, Object>> rows,
      boolean dhh)
      throws SQLException {
    // table/columns 只来自本类静态白名单。逐行转换并分批写入，避免复制整份底表。
    // row_hash 上已有唯一索引，INSERT IGNORE 在数据库侧完成去重。
    String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
    String sql = "INSERT IGNORE INTO `" + table + "` (`" + String.join("`, `", columns)
        + "`) VALUES (" + placeholders + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int pending = 0;
      int inserted = 0;
      for (Map<String, Object> row : rows) {
        List<Object> values = dhh ? dhhValues(row) : jdValues(row);
        for (int index = 0; index < values.size(); index++) {
          statement.setObject(index + 1, values.get(index));
        }
        statement.addBatch();
        if (++pending % 500 == 0) inserted += insertedCount(statement.executeBatch());
      }
      if (pending % 500 != 0) inserted += insertedCount(statement.executeBatch());
      return inserted;
    }
  }

  private int insertJdLowActivityRows(
      Connection connection,
      List<Map<String, Object>> rows) throws SQLException {
    String placeholders =
        String.join(", ", java.util.Collections.nCopies(JD_LOW_ACTIVITY_COLUMNS.size(), "?"));
    String sql = "INSERT IGNORE INTO `jd_low_activity_plan_rows` (`"
        + String.join("`, `", JD_LOW_ACTIVITY_COLUMNS) + "`) VALUES (" + placeholders + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int pending = 0;
      int inserted = 0;
      for (Map<String, Object> row : rows) {
        List<Object> values = jdLowActivityValues(row);
        for (int index = 0; index < values.size(); index++) {
          statement.setObject(index + 1, values.get(index));
        }
        statement.addBatch();
        if (++pending % 500 == 0) inserted += insertedCount(statement.executeBatch());
      }
      if (pending % 500 != 0) inserted += insertedCount(statement.executeBatch());
      return inserted;
    }
  }

  private static int insertedCount(int[] results) {
    int inserted = 0;
    for (int result : results) {
      if (result > 0) inserted += result;
      else if (result == Statement.SUCCESS_NO_INFO) inserted++;
    }
    return inserted;
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

  @PreDestroy
  void close() {
    dataSource.close();
  }
}
