package com.rockorca.bi;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AdpfluxRepository {
  private final DataSource dataSource;
  private final ObjectMapper objectMapper;

  public AdpfluxRepository(ReportRepository reports, ObjectMapper objectMapper) {
    this.dataSource = reports.dataSource();
    this.objectMapper = objectMapper;
  }

  public void initialize() {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS `adpflux_advertiser_daily` (
            `business_date` DATE NOT NULL,
            `advertiser_id` VARCHAR(100) NOT NULL,
            `advertiser_name` VARCHAR(500) NOT NULL DEFAULT '',
            `balance` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `billed_cost` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `cash_spend` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `voucher_spend` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `total_spend` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `clicks` BIGINT NOT NULL DEFAULT 0,
            `conversions` BIGINT NOT NULL DEFAULT 0,
            `impressions` BIGINT NOT NULL DEFAULT 0,
            `unique_reach` BIGINT NOT NULL DEFAULT 0,
            `cpc` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `cpm` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `ctr` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `cpa` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `cvr` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `time_attr_conversions` BIGINT NOT NULL DEFAULT 0,
            `time_attr_cpa` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `time_attr_cvr` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `skan_conversions` BIGINT NOT NULL DEFAULT 0,
            `skan_cpa` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `skan_cvr` DECIMAL(18, 4) NOT NULL DEFAULT 0,
            `currency` VARCHAR(20) NOT NULL DEFAULT '',
            `company_name` VARCHAR(500) NOT NULL DEFAULT '',
            `account_type` VARCHAR(50) NOT NULL DEFAULT '',
            `contact` VARCHAR(500) NOT NULL DEFAULT '',
            `status` INT NOT NULL DEFAULT 0,
            `status_raw` VARCHAR(100) NOT NULL DEFAULT '',
            `timezone` VARCHAR(50) NOT NULL DEFAULT '',
            `closing_time` VARCHAR(100) NOT NULL DEFAULT '',
            `raw_json` MEDIUMTEXT NULL,
            `synced_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3),
            PRIMARY KEY (`business_date`, `advertiser_id`),
            KEY `idx_adpflux_account_date` (`advertiser_id`, `business_date`),
            KEY `idx_adpflux_date_spend` (`business_date`, `total_spend`)
          ) ENGINE=InnoDB COMMENT='ADPFlux账户每日看板快照'
          """);
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS `adpflux_sync_runs` (
            `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
            `start_date` DATE NOT NULL,
            `end_date` DATE NOT NULL,
            `row_count` INT NOT NULL DEFAULT 0,
            `trigger_type` VARCHAR(30) NOT NULL DEFAULT 'manual',
            `finished_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
            PRIMARY KEY (`id`),
            KEY `idx_adpflux_sync_finished` (`finished_at`)
          ) ENGINE=InnoDB COMMENT='ADPFlux账户看板同步记录'
          """);
      ensureTikTokColumns(connection);
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private static void ensureTikTokColumns(Connection connection) throws SQLException {
    ensureColumn(connection, "impressions", "BIGINT NOT NULL DEFAULT 0");
    ensureColumn(connection, "unique_reach", "BIGINT NOT NULL DEFAULT 0");
    ensureColumn(connection, "cpc", "DECIMAL(18,4) NOT NULL DEFAULT 0");
    ensureColumn(connection, "cpm", "DECIMAL(18,4) NOT NULL DEFAULT 0");
    ensureColumn(connection, "ctr", "DECIMAL(18,4) NOT NULL DEFAULT 0");
    ensureColumn(connection, "time_attr_conversions", "BIGINT NOT NULL DEFAULT 0");
    ensureColumn(connection, "time_attr_cpa", "DECIMAL(18,4) NOT NULL DEFAULT 0");
    ensureColumn(connection, "time_attr_cvr", "DECIMAL(18,4) NOT NULL DEFAULT 0");
    ensureColumn(connection, "skan_conversions", "BIGINT NOT NULL DEFAULT 0");
    ensureColumn(connection, "skan_cpa", "DECIMAL(18,4) NOT NULL DEFAULT 0");
    ensureColumn(connection, "skan_cvr", "DECIMAL(18,4) NOT NULL DEFAULT 0");
    ensureColumn(connection, "company_name", "VARCHAR(500) NOT NULL DEFAULT ''");
    ensureColumn(connection, "account_type", "VARCHAR(50) NOT NULL DEFAULT ''");
    ensureColumn(connection, "contact", "VARCHAR(500) NOT NULL DEFAULT ''");
  }

  private static void ensureColumn(Connection connection, String column, String definition)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement("""
        SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'adpflux_advertiser_daily'
           AND COLUMN_NAME = ?
        """)) {
      query.setString(1, column);
      try (ResultSet result = query.executeQuery()) {
        if (result.next() && result.getInt(1) > 0) return;
      }
    }
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "ALTER TABLE adpflux_advertiser_daily ADD COLUMN `" + column + "` " + definition);
    }
  }

  public void replaceRange(
      List<Map<String, Object>> rows,
      String startValue,
      String endValue,
      String triggerType) {
    LocalDate start = LocalDate.parse(startValue);
    LocalDate end = LocalDate.parse(endValue);
    String insertSql = """
        INSERT INTO adpflux_advertiser_daily (
          business_date, advertiser_id, advertiser_name, balance, billed_cost,
          cash_spend, voucher_spend, total_spend, clicks, conversions,
          impressions, unique_reach, cpc, cpm, ctr, cpa, cvr,
          time_attr_conversions, time_attr_cpa, time_attr_cvr,
          skan_conversions, skan_cpa, skan_cvr,
          currency, company_name, account_type, contact,
          status, status_raw, timezone, closing_time, raw_json
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement delete = connection.prepareStatement("""
            DELETE FROM adpflux_advertiser_daily
             WHERE business_date BETWEEN ? AND ?
            """)) {
          delete.setDate(1, Date.valueOf(start));
          delete.setDate(2, Date.valueOf(end));
          delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
          for (Map<String, Object> row : rows) {
            insert.setDate(1, Date.valueOf(ReportService.text(row.get("date"))));
            insert.setString(2, ReportService.text(row.get("advertiserId")));
            insert.setString(3, ReportService.text(row.get("advertiserName")));
            insert.setDouble(4, ReportService.number(row.get("balance")));
            insert.setDouble(5, ReportService.number(row.get("billedCost")));
            insert.setDouble(6, ReportService.number(row.get("cashSpend")));
            insert.setDouble(7, ReportService.number(row.get("voucherSpend")));
            insert.setDouble(8, ReportService.number(row.get("totalSpend")));
            insert.setLong(9, Math.round(ReportService.number(row.get("clicks"))));
            insert.setLong(10, Math.round(ReportService.number(row.get("conversions"))));
            insert.setLong(11, Math.round(ReportService.number(row.get("impressions"))));
            insert.setLong(12, Math.round(ReportService.number(row.get("uniqueReach"))));
            insert.setDouble(13, ReportService.number(row.get("cpc")));
            insert.setDouble(14, ReportService.number(row.get("cpm")));
            insert.setDouble(15, ReportService.number(row.get("ctr")));
            insert.setDouble(16, ReportService.number(row.get("cpa")));
            insert.setDouble(17, ReportService.number(row.get("cvr")));
            insert.setLong(18, Math.round(ReportService.number(row.get("timeAttributedConversions"))));
            insert.setDouble(19, ReportService.number(row.get("timeAttributedCpa")));
            insert.setDouble(20, ReportService.number(row.get("timeAttributedCvr")));
            insert.setLong(21, Math.round(ReportService.number(row.get("skanConversions"))));
            insert.setDouble(22, ReportService.number(row.get("skanCpa")));
            insert.setDouble(23, ReportService.number(row.get("skanCvr")));
            insert.setString(24, ReportService.text(row.get("currency")));
            insert.setString(25, ReportService.text(row.get("companyName")));
            insert.setString(26, ReportService.text(row.get("accountType")));
            insert.setString(27, ReportService.text(row.get("contact")));
            insert.setInt(28, (int) ReportService.number(row.get("status")));
            insert.setString(29, ReportService.text(row.get("statusRaw")));
            insert.setString(30, ReportService.text(row.get("timezone")));
            insert.setString(31, ReportService.text(row.get("closingTime")));
            insert.setString(32, objectMapper.writeValueAsString(row.get("raw")));
            insert.addBatch();
          }
          insert.executeBatch();
        }
        try (PreparedStatement sync = connection.prepareStatement("""
            INSERT INTO adpflux_sync_runs
              (start_date, end_date, row_count, trigger_type)
            VALUES (?, ?, ?, ?)
            """)) {
          sync.setDate(1, Date.valueOf(start));
          sync.setDate(2, Date.valueOf(end));
          sync.setInt(3, rows.size());
          sync.setString(4, ReportService.text(triggerType));
          sync.executeUpdate();
        }
        connection.commit();
      } catch (Exception error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    } catch (Exception error) {
      if (error instanceof SQLException sqlError) throw databaseError(sqlError);
      throw new IllegalStateException("保存 ADPFlux 数据失败：" + error.getMessage(), error);
    }
  }

  public List<Map<String, Object>> readRows(
      String startValue,
      String endValue,
      String queryValue,
      String statusValue,
      boolean spendingOnly) {
    StringBuilder sql = new StringBuilder("""
        SELECT DATE_FORMAT(business_date, '%Y-%m-%d') AS business_date,
               advertiser_id, advertiser_name, balance, billed_cost, cash_spend,
               voucher_spend, total_spend, clicks, conversions,
               impressions, unique_reach, cpc, cpm, ctr, cpa, cvr,
               time_attr_conversions, time_attr_cpa, time_attr_cvr,
               skan_conversions, skan_cpa, skan_cvr,
               currency, company_name, account_type, contact,
               status, status_raw, timezone, closing_time
          FROM adpflux_advertiser_daily
         WHERE business_date BETWEEN ? AND ?
        """);
    List<Object> parameters = new ArrayList<>();
    parameters.add(Date.valueOf(LocalDate.parse(startValue)));
    parameters.add(Date.valueOf(LocalDate.parse(endValue)));
    String query = ReportService.text(queryValue);
    if (!query.isBlank()) {
      sql.append(" AND (advertiser_id LIKE ? OR advertiser_name LIKE ? OR company_name LIKE ?)");
      parameters.add("%" + query + "%");
      parameters.add("%" + query + "%");
      parameters.add("%" + query + "%");
    }
    String status = ReportService.text(statusValue);
    if (status.equals("enabled")) {
      sql.append(" AND status = 1");
    } else if (status.equals("disabled")) {
      sql.append(" AND status <> 1");
    }
    if (spendingOnly) sql.append(" AND total_spend > 0");
    sql.append(" ORDER BY business_date DESC, total_spend DESC, advertiser_id");

    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      for (int index = 0; index < parameters.size(); index++) {
        statement.setObject(index + 1, parameters.get(index));
      }
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          rows.add(ReportService.mapOf(
              "date", result.getString("business_date"),
              "advertiserId", result.getString("advertiser_id"),
              "advertiserName", result.getString("advertiser_name"),
              "balance", result.getDouble("balance"),
              "billedCost", result.getDouble("billed_cost"),
              "cashSpend", result.getDouble("cash_spend"),
              "voucherSpend", result.getDouble("voucher_spend"),
              "totalSpend", result.getDouble("total_spend"),
              "clicks", result.getLong("clicks"),
              "conversions", result.getLong("conversions"),
              "impressions", result.getLong("impressions"),
              "uniqueReach", result.getLong("unique_reach"),
              "cpc", result.getDouble("cpc"),
              "cpm", result.getDouble("cpm"),
              "ctr", result.getDouble("ctr"),
              "cpa", result.getDouble("cpa"),
              "cvr", result.getDouble("cvr"),
              "timeAttributedConversions", result.getLong("time_attr_conversions"),
              "timeAttributedCpa", result.getDouble("time_attr_cpa"),
              "timeAttributedCvr", result.getDouble("time_attr_cvr"),
              "skanConversions", result.getLong("skan_conversions"),
              "skanCpa", result.getDouble("skan_cpa"),
              "skanCvr", result.getDouble("skan_cvr"),
              "currency", result.getString("currency"),
              "companyName", result.getString("company_name"),
              "accountType", result.getString("account_type"),
              "contact", result.getString("contact"),
              "status", result.getInt("status"),
              "statusRaw", result.getString("status_raw"),
              "timezone", result.getString("timezone"),
              "closingTime", result.getString("closing_time")));
        }
      }
      return rows;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public String latestSyncTime() {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT DATE_FORMAT(MAX(finished_at), '%Y-%m-%dT%H:%i:%s')
               FROM adpflux_sync_runs
             """);
         ResultSet result = statement.executeQuery()) {
      return result.next() ? ReportService.text(result.getString(1)) : "";
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private static IllegalStateException databaseError(SQLException error) {
    return new IllegalStateException("ADPFlux 数据库操作失败：" + error.getMessage(), error);
  }
}
