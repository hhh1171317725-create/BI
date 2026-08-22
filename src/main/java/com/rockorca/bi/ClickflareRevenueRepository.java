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

/** Stores ClickFlare campaign revenue snapshots so report reads never wait for the upstream API. */
@Repository
public class ClickflareRevenueRepository {
  private final DataSource dataSource;

  public ClickflareRevenueRepository(ReportRepository reports) {
    this.dataSource = reports.dataSource();
  }

  public void initialize() {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS `clickflare_campaign_revenue_daily` (
            `business_date` DATE NOT NULL COMMENT '收益业务日期，北京时间',
            `campaign_id` VARCHAR(100) NOT NULL COMMENT 'ClickFlare收益活动ID',
            `campaign_name` VARCHAR(500) NOT NULL DEFAULT '' COMMENT 'ClickFlare收益活动名称',
            `conversions` BIGINT NOT NULL DEFAULT 0 COMMENT '转化数',
            `revenue` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '收益金额',
            `spend` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '上游回传消耗',
            `roi` DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '上游回传ROI',
            `currency` VARCHAR(20) NOT NULL DEFAULT '' COMMENT '币种',
            `synced_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近同步写入时间',
            PRIMARY KEY (`business_date`, `campaign_id`),
            KEY `idx_clickflare_date_revenue` (`business_date`, `revenue`),
            KEY `idx_clickflare_campaign_date` (`campaign_id`, `business_date`)
          ) ENGINE=InnoDB COMMENT='ClickFlare活动每日收益快照'
          """);
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS `clickflare_revenue_sync_runs` (
            `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '同步记录主键',
            `business_date` DATE NOT NULL COMMENT '同步的收益日期',
            `row_count` INT NOT NULL DEFAULT 0 COMMENT '成功写入的活动数量',
            `trigger_type` VARCHAR(30) NOT NULL DEFAULT 'scheduled'
              COMMENT '触发方式：scheduled定时、manual手动',
            `finished_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              COMMENT '同步完成时间',
            PRIMARY KEY (`id`),
            KEY `idx_clickflare_sync_date` (`business_date`, `finished_at`)
          ) ENGINE=InnoDB COMMENT='ClickFlare收益同步记录'
          """);
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  /** Replaces one complete date atomically; failed upstream requests never erase the last snapshot. */
  public void replaceDate(String dateValue, List<Map<String, Object>> rows, String triggerType) {
    LocalDate date = LocalDate.parse(dateValue);
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement delete = connection.prepareStatement("""
            DELETE FROM clickflare_campaign_revenue_daily WHERE business_date = ?
            """)) {
          delete.setDate(1, Date.valueOf(date));
          delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO clickflare_campaign_revenue_daily (
              business_date, campaign_id, campaign_name, conversions,
              revenue, spend, roi, currency
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
          for (Map<String, Object> row : rows) {
            insert.setDate(1, Date.valueOf(date));
            insert.setString(2, ReportService.text(row.get("campaignId")));
            insert.setString(3, ReportService.text(row.get("campaignName")));
            insert.setLong(4, Math.round(ReportService.number(row.get("conversions"))));
            insert.setDouble(5, ReportService.number(row.get("revenue")));
            insert.setDouble(6, ReportService.number(row.get("spend")));
            insert.setDouble(7, ReportService.number(row.get("roi")));
            insert.setString(8, ReportService.text(row.get("currency")));
            insert.addBatch();
          }
          insert.executeBatch();
        }
        try (PreparedStatement sync = connection.prepareStatement("""
            INSERT INTO clickflare_revenue_sync_runs
              (business_date, row_count, trigger_type)
            VALUES (?, ?, ?)
            """)) {
          sync.setDate(1, Date.valueOf(date));
          sync.setInt(2, rows.size());
          sync.setString(3, ReportService.text(triggerType));
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
      throw new IllegalStateException("保存 ClickFlare 收益数据失败：" + error.getMessage(), error);
    }
  }

  public List<Map<String, Object>> readDate(String dateValue) {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT campaign_id, campaign_name, conversions, revenue, spend, roi, currency
               FROM clickflare_campaign_revenue_daily
              WHERE business_date = ?
              ORDER BY revenue DESC, campaign_name, campaign_id
             """)) {
      statement.setDate(1, Date.valueOf(LocalDate.parse(dateValue)));
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          rows.add(ReportService.mapOf(
              "campaignId", result.getString("campaign_id"),
              "campaignName", result.getString("campaign_name"),
              "conversions", result.getLong("conversions"),
              "revenue", result.getDouble("revenue"),
              "spend", result.getDouble("spend"),
              "roi", result.getDouble("roi"),
              "currency", result.getString("currency")));
        }
      }
      return rows;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  /** Aggregates saved daily campaign snapshots for a report date range. */
  public List<Map<String, Object>> readRange(String startValue, String endValue) {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT campaign_id,
                    MAX(campaign_name) AS campaign_name,
                    SUM(conversions) AS conversions,
                    SUM(revenue) AS revenue,
                    SUM(spend) AS spend,
                    CASE WHEN SUM(spend) = 0 THEN 0 ELSE SUM(revenue) / SUM(spend) END AS roi,
                    MAX(currency) AS currency
               FROM clickflare_campaign_revenue_daily
              WHERE business_date BETWEEN ? AND ?
              GROUP BY campaign_id
              ORDER BY revenue DESC, campaign_name, campaign_id
             """)) {
      statement.setDate(1, Date.valueOf(LocalDate.parse(startValue)));
      statement.setDate(2, Date.valueOf(LocalDate.parse(endValue)));
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          rows.add(ReportService.mapOf(
              "campaignId", result.getString("campaign_id"),
              "campaignName", result.getString("campaign_name"),
              "conversions", result.getLong("conversions"),
              "revenue", result.getDouble("revenue"),
              "spend", result.getDouble("spend"),
              "roi", result.getDouble("roi"),
              "currency", result.getString("currency")));
        }
      }
      return rows;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public String latestSyncTime(String dateValue) {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT DATE_FORMAT(MAX(finished_at), '%Y-%m-%dT%H:%i:%s')
               FROM clickflare_revenue_sync_runs
              WHERE business_date = ?
             """)) {
      statement.setDate(1, Date.valueOf(LocalDate.parse(dateValue)));
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? ReportService.text(result.getString(1)) : "";
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public String latestSyncTime(String startValue, String endValue) {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT DATE_FORMAT(MAX(finished_at), '%Y-%m-%dT%H:%i:%s')
               FROM clickflare_revenue_sync_runs
              WHERE business_date BETWEEN ? AND ?
             """)) {
      statement.setDate(1, Date.valueOf(LocalDate.parse(startValue)));
      statement.setDate(2, Date.valueOf(LocalDate.parse(endValue)));
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? ReportService.text(result.getString(1)) : "";
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private static IllegalStateException databaseError(SQLException error) {
    return new IllegalStateException("ClickFlare 收益数据库操作失败：" + error.getMessage(), error);
  }
}
