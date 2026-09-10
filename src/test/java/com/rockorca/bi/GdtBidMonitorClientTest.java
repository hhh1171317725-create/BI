package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

class GdtBidMonitorClientTest {
  @Test void springCanCreateTheProductionClient(){
    try(var context=new AnnotationConfigApplicationContext()){
      context.registerBean(ObjectMapper.class,()->new ObjectMapper());context.register(GdtBidMonitorClient.class);context.refresh();
      assertNotNull(context.getBean(GdtBidMonitorClient.class));
    }
  }
  @Test void buildsTheVerifiedGdtReportRequest(){
    var input=Map.<String,Object>of("createdStart","2026-09-06","createdEnd","2026-09-09");
    var body=GdtBidMonitorClient.requestBody(input,LocalDate.parse("2026-09-09"),LocalDate.parse("2026-09-09"),3);
    assertEquals("gdt_upgrade",body.get("media_type"));assertEquals(100,body.get("page_size"));assertEquals(3,body.get("page"));
    assertEquals("adgroup_id",body.get("sort_field"));
    var conditions=(Map<?,?>)body.get("conditions");assertEquals(List.of("2026-09-06","2026-09-09"),conditions.get("created_time"));
    assertTrue(((List<?>)body.get("base_infos")).containsAll(List.of("advertiser_nick","user_name","deep_bid_amount")));
    assertTrue(((List<?>)body.get("kpis")).contains("reg_pv"));
  }

  @Test void mapsGdtFieldsIntoTheSharedBidMonitorSchema(){
    var raw=new LinkedHashMap<String,Object>();
    raw.put("adgroup_id","134008243258");raw.put("adgroup_name","广点通计划");
    raw.put("advertiser_id","89696535");raw.put("media_account_id","12628542431");
    raw.put("advertiser_nick","账户甲");raw.put("user_name","优化师甲");raw.put("created_time","2026-09-09 10:00:00");
    raw.put("cost","123.45");raw.put("view_count","10000");raw.put("conversions_count","12");raw.put("reg_pv","56");raw.put("deep_conversions_count","34");raw.put("bid_amount","5.5");
    raw.put("deep_bid_amount","8.8");raw.put("bid_mode_name","自动出价");raw.put("optimization_goal_name","注册");
    raw.put("deep_conversion_spec_name","付费");raw.put("system_status_name","投放中");
    var result=GdtBidMonitorClient.parse(Map.of("code",0,"data",Map.of("list",List.of(raw),"page_info",Map.of("total_count",301))),1,"chuangliang_session=secret");
    assertEquals(301,result.get("total"));var row=(Map<?,?>)((List<?>)result.get("rows")).getFirst();
    assertEquals("134008243258",row.get("promotion_id"));assertEquals("89696535",row.get("advertiser_id"));
    assertEquals("123.45",row.get("stat_cost"));assertEquals("12",row.get("convert_cnt"));assertEquals("56",row.get("active_register"));
    assertEquals("5.5",row.get("cpa_bid"));assertEquals("10000",row.get("show_cnt"));assertEquals("账户甲",row.get("media_account_name"));assertEquals("优化师甲",row.get("user_name"));
    assertEquals("自动出价",row.get("deep_bid_type_text"));assertEquals("付费",row.get("deep_external_action_text"));
    assertEquals("注册",row.get("external_action_text"));assertEquals("投放中",row.get("status_text"));
    assertEquals("gdt",row.get("source_platform"));assertEquals("广点通",row.get("platform_text"));
    var provider=(Map<?,?>)row.get("provider_data");assertEquals("34",provider.get("deep_conversions_count"));
  }

  @Test void keepsZeroMetricsAndRejectsInvalidIds(){
    var raw=new LinkedHashMap<String,Object>();raw.put("adgroup_id","1");raw.put("advertiser_id","2");raw.put("media_account_id","3");
    var result=GdtBidMonitorClient.parse(Map.of("code",0,"data",Map.of("list",List.of(raw),"page_info",Map.of("total_count",1))),1,"chuangliang_session=x");
    var row=(Map<?,?>)((List<?>)result.get("rows")).getFirst();assertEquals("0",row.get("stat_cost"));assertEquals("0",row.get("active_register"));
    raw.put("adgroup_id",1.0d);assertThrows(IllegalArgumentException.class,()->GdtBidMonitorClient.parse(
        Map.of("code",0,"data",Map.of("list",List.of(raw),"page_info",Map.of("total_count",1))),1,"chuangliang_session=x"));
  }
}
