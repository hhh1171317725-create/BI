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
import org.springframework.stereotype.Repository;

/** Stores one AFS overview snapshot per channel/style mapping and Beijing business date. */
@Repository
public class AfsMonitorRepository {
  private final ReportRepository reports;

  public AfsMonitorRepository(ReportRepository reports) {
    this.reports = reports;
  }

  public void initialize() {
    try (Connection connection = reports.openConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS afs_style_daily (
            business_date DATE NOT NULL COMMENT '北京时间业务日期',
            channel_id VARCHAR(255) NOT NULL COMMENT '账户对应关系中的channel',
            style_id VARCHAR(255) NOT NULL COMMENT '账户对应关系中的style ID',
            lp1_page_views BIGINT NOT NULL DEFAULT 0 COMMENT 'LP1页面浏览次数',
            related_search_load_results BIGINT NOT NULL DEFAULT 0 COMMENT '相关搜索返回结果次数',
            related_search_loaded BIGINT NOT NULL DEFAULT 0 COMMENT '相关搜索成功加载次数',
            related_search_no_fill BIGINT NOT NULL DEFAULT 0 COMMENT '相关搜索无填充次数',
            related_search_unknown BIGINT NOT NULL DEFAULT 0 COMMENT '相关搜索未知状态次数',
            related_search_render_success BIGINT NOT NULL DEFAULT 0 COMMENT '相关搜索渲染成功次数',
            page_views BIGINT NOT NULL DEFAULT 0 COMMENT 'AFS页面浏览次数',
            afs_render_success BIGINT NOT NULL DEFAULT 0 COMMENT 'AFS广告渲染成功次数',
            afs_ad_clicks BIGINT NOT NULL DEFAULT 0 COMMENT 'AFS广告点击次数',
            synced_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近同步时间',
            PRIMARY KEY (business_date, channel_id, style_id),
            KEY idx_afs_mapping_synced (channel_id, style_id, synced_at)
          ) ENGINE=InnoDB COMMENT='AFS监控channel与style每日汇总'
          """);
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public void upsert(Map<String, Object> row) {
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             INSERT INTO afs_style_daily (
               business_date, channel_id, style_id, lp1_page_views, related_search_load_results,
               related_search_loaded, related_search_no_fill, related_search_unknown,
               related_search_render_success, page_views, afs_render_success, afs_ad_clicks
             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
             ON DUPLICATE KEY UPDATE
               lp1_page_views=VALUES(lp1_page_views),
               related_search_load_results=VALUES(related_search_load_results),
               related_search_loaded=VALUES(related_search_loaded),
               related_search_no_fill=VALUES(related_search_no_fill),
               related_search_unknown=VALUES(related_search_unknown),
               related_search_render_success=VALUES(related_search_render_success),
               page_views=VALUES(page_views),
               afs_render_success=VALUES(afs_render_success),
               afs_ad_clicks=VALUES(afs_ad_clicks), synced_at=CURRENT_TIMESTAMP(3)
             """)) {
      statement.setDate(1, Date.valueOf(LocalDate.parse(ReportService.text(row.get("date")))));
      statement.setString(2, ReportService.text(row.get("channelId")));
      statement.setString(3, ReportService.text(row.get("styleId")));
      String[] fields = {"lp1PageViews", "relatedSearchLoadResults", "relatedSearchLoaded",
          "relatedSearchNoFill", "relatedSearchUnknown", "relatedSearchRenderSuccess",
          "pageViews", "afsRenderSuccess", "afsAdClicks"};
      for (int index = 0; index < fields.length; index++) {
        statement.setLong(index + 4, Math.round(ReportService.number(row.get(fields[index]))));
      }
      statement.executeUpdate();
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public List<Map<String, Object>> readRange(
      String channelId, String styleId, String startValue, String endValue) {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT business_date, lp1_page_views, related_search_load_results,
                    related_search_loaded, related_search_no_fill, related_search_unknown,
                    related_search_render_success, page_views, afs_render_success,
                    afs_ad_clicks, DATE_FORMAT(synced_at, '%Y-%m-%dT%H:%i:%s') AS synced_at
               FROM afs_style_daily
              WHERE channel_id = ? AND style_id = ? AND business_date BETWEEN ? AND ?
              ORDER BY business_date
             """)) {
      statement.setString(1, channelId);
      statement.setString(2, styleId);
      statement.setDate(3, Date.valueOf(LocalDate.parse(startValue)));
      statement.setDate(4, Date.valueOf(LocalDate.parse(endValue)));
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) rows.add(map(result, channelId, styleId));
      }
      return rows;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private static Map<String, Object> map(
      ResultSet result, String channelId, String styleId) throws SQLException {
    return ReportService.mapOf(
        "date", result.getDate("business_date").toString(),
        "channelId", channelId, "styleId", styleId,
        "lp1PageViews", result.getLong("lp1_page_views"),
        "relatedSearchLoadResults", result.getLong("related_search_load_results"),
        "relatedSearchLoaded", result.getLong("related_search_loaded"),
        "relatedSearchNoFill", result.getLong("related_search_no_fill"),
        "relatedSearchUnknown", result.getLong("related_search_unknown"),
        "relatedSearchRenderSuccess", result.getLong("related_search_render_success"),
        "pageViews", result.getLong("page_views"),
        "afsRenderSuccess", result.getLong("afs_render_success"),
        "afsAdClicks", result.getLong("afs_ad_clicks"),
        "syncedAt", result.getString("synced_at"));
  }

  private static IllegalStateException databaseError(SQLException error) {
    return new IllegalStateException("AFS监控数据库操作失败：" + error.getMessage(), error);
  }
}
