package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JdLowActivityUpstreamServiceTest {
  @Test
  void mapsAccountDayRowAndConvertsSpendFromThousandthsOfYuan() {
    JdLowActivityUpstreamService service = new JdLowActivityUpstreamService(
        mock(RuntimeConfig.class),
        new ObjectMapper(),
        mock(HttpClient.class));
    long timestamp = LocalDate.of(2026, 7, 30)
        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
        .toEpochSecond();
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("dt", timestamp);
    source.put("admin_user", "管理员A");
    source.put("advertiser_user", "京东低活任务");
    source.put("advertiser_id", "1836501056726024");
    source.put("advertiser_name", "测试账户");
    source.put("ad_cost", 600120);
    source.put("reward_order_amount", 32.5);
    source.put("view", 10000);
    source.put("click", 120);

    Map<String, Object> row = service.mapRow(source);

    assertEquals("2026-07-30", row.get("日期"));
    assertEquals(600.12, row.get("消耗"));
    assertEquals(32.5, row.get("佣金"));
    assertEquals("1836501056726024", row.get("计划ID"));
    assertEquals("测试账户", row.get("计划名称"));
    assertFalse((Boolean) row.get("独立计划维度"));
  }

  @Test
  void ignoresSummaryRowsWithoutDateOrAdvertiser() {
    JdLowActivityUpstreamService service = new JdLowActivityUpstreamService(
        mock(RuntimeConfig.class),
        new ObjectMapper(),
        mock(HttpClient.class));

    assertNull(service.mapRow(Map.of("ad_cost", 123456)));
  }
}
