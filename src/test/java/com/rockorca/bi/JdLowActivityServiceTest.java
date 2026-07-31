package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdLowActivityServiceTest {
  @Test
  void aggregatesAccountAndDateMetricsUsingCommissionMinusSpend() {
    JdLowActivityService service = new JdLowActivityService(
        mock(ReportRepository.class),
        mock(JdLowActivityUpstreamService.class),
        mock(RuntimeConfig.class));
    List<Map<String, Object>> rows = List.of(
        row("2026-07-29", 100, 25, 10_000, 100, 20, 15, 5, 10, 80, 4, 8),
        row("2026-07-30", 200, 50, 20_000, 100, 30, 25, 5, 20, 90, 6, 12));

    Map<String, Object> report = service.buildAnalysis(rows, "2026-07-31T01:00:00Z");
    Map<String, Object> summary = objectMap(report.get("summary"));

    assertEquals(300.0, summary.get("消耗"));
    assertEquals(75.0, summary.get("佣金"));
    assertEquals(-225.0, summary.get("利润"));
    assertEquals(0.25, summary.get("ROI"));
    assertEquals(1.5, summary.get("CPC"));
    assertEquals(10.0, summary.get("CPM"));
    assertEquals(7.5, summary.get("转化成本"));
    assertEquals(10.0, summary.get("有效订单成本"));
    assertEquals(1, summary.get("账户数"));
    assertEquals(1, summary.get("计划数"));
    assertEquals(2, summary.get("数据天数"));
    assertFalse((Boolean) report.get("sourcePlanAvailable"));
    assertTrue(String.valueOf(report.get("planDimensionNote")).contains("按账户回退"));

    List<Map<String, Object>> byDate = objectList(report.get("by_date"));
    assertEquals("2026-07-30", byDate.getFirst().get("日期"));
    assertEquals(2, byDate.size());
    assertEquals(1, objectList(report.get("by_account")).size());
  }

  private static Map<String, Object> row(
      String date,
      double spend,
      double commission,
      double impressions,
      double clicks,
      double conversions,
      double successful,
      double filtered,
      double validOrders,
      double validClicks,
      double t3Orders,
      double totalOrders) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("日期", date);
    row.put("管理员", "管理员A");
    row.put("任务", "低活任务A");
    row.put("账户ID", "10001");
    row.put("账户名称", "账户A");
    row.put("计划ID", "10001");
    row.put("计划名称", "账户A");
    row.put("独立计划维度", false);
    row.put("消耗", spend);
    row.put("佣金", commission);
    row.put("展现", impressions);
    row.put("点击", clicks);
    row.put("转化数", conversions);
    row.put("成功转化数", successful);
    row.put("过滤转化数", filtered);
    row.put("有效父订单数", validOrders);
    row.put("有效点击UV", validClicks);
    row.put("T3订单数", t3Orders);
    row.put("总订单数", totalOrders);
    return row;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> objectList(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
