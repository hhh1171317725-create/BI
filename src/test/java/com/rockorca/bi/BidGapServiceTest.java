package com.rockorca.bi;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BidGapServiceTest {
  @Test void unsettledRegistrationsUseEarlierPriceForAccountAndTask(){
    var current=new LinkedHashMap<String,Object>(pricedRow("2026-09-08",0,0,100));current.put("任务名","任务甲");
    var prior=new LinkedHashMap<String,Object>(pricedRow("2026-09-06",60,20,100));prior.put("任务名","任务甲");
    var result=BidGapService.calculate(List.of(current,prior),LocalDate.of(2026,9,10));
    var account=(Map<?,?>)((Map<?,?>)result.get("accounts")).get("123");
    var task=(Map<?,?>)((Map<?,?>)result.get("tasks")).get("任务甲");
    for(Object value:List.of(account.get("dailyPrice"),((Map<?,?>)account.get("dailyPricesByTask")).get("任务甲"),task.get("dailyPrice"))){
      var price=(Map<?,?>)value;assertEquals(3d,price.get("price"));assertEquals("2026-09-06",price.get("date"));assertEquals("2026-09-08",price.get("fallbackFrom"));
    }
    current.put("注册数",0);
    var noRegistrations=BidGapService.calculate(List.of(current,prior),LocalDate.of(2026,9,10));
    assertNull(((Map<?,?>)((Map<?,?>)((Map<?,?>)noRegistrations.get("accounts")).get("123")).get("dailyPrice")).get("price"));
  }
  @Test void loadQueriesThirtyPriorDaysForTaskPriceAndKeepsGapWindow(){
    var repository=org.mockito.Mockito.mock(ReportRepository.class);
    var reports=org.mockito.Mockito.mock(ReportService.class);
    org.mockito.Mockito.when(repository.readDhhRows("2026-08-03","2026-09-01","")).thenReturn(List.of());
    org.mockito.Mockito.when(reports.buildDhhAccountRows(List.of())).thenReturn(List.of());
    var result=new BidGapService(repository,reports).load("2026-09-02");
    org.mockito.Mockito.verify(repository).readDhhRows("2026-08-03","2026-09-01","");
    assertEquals("2026-09-02",result.get("anchor"));
    assertEquals("2026-08-29",result.get("start"));
    assertEquals("2026-08-31",result.get("end"));
    assertEquals("2026-08-31",result.get("priceDate"));
  }
  private Map<String,Object> row(String date,double settled,double registered){return Map.of("账户ID","123", "日期",date,"结算数",settled,"注册数",registered);}
  private Map<String,Object> pricedRow(String date,double commission,double settled,double registered){return Map.of("账户ID","123","日期",date,"预估佣金",commission,"结算数",settled,"注册数",registered);}
  private Map<String,Object> taskRow(String date,String task,double spend){return Map.of("账户ID","123","日期",date,"任务名",task,"消耗",spend,"预估佣金",0,"结算数",0,"注册数",0);}
  @SuppressWarnings("unchecked")
  @Test void dividesThreeDaySettlementTotalByRegistrationTotalAfterCombiningSameDayRows(){
    var result=BidGapService.calculate(List.of(row("2026-09-05",40,100),row("2026-09-05",10,100),row("2026-09-06",90,100),row("2026-09-07",60,100),row("2026-09-08",900,100),row("2026-09-09",900,100),row("2026-09-04",900,100)),LocalDate.of(2026,9,9));
    var account=(Map<String,Object>)((Map<?,?>)result.get("accounts")).get("123");
    assertEquals(.5,(double)account.get("gap"),1e-12);
    assertEquals(200d,account.get("gapSettlements"));assertEquals(400d,account.get("gapRegistrations"));
    assertEquals(3,account.get("validDays"));
    assertEquals("2026-09-05",result.get("start"));assertEquals("2026-09-07",result.get("end"));
  }
  @Test void zeroSettlementAndZeroRegistrationDaysAreExcludedFromGap(){
    var result=BidGapService.calculate(List.of(row("2026-09-05",0,100),row("2026-09-06",20,100),row("2026-09-07",10,0)),LocalDate.of(2026,9,9));
    var account=(Map<?,?>)((Map<?,?>)result.get("accounts")).get("123");
    assertEquals(.2,account.get("gap"));assertEquals(1,account.get("validDays"));
    var days=(List<?>)account.get("days");assertTrue(((Map<?,?>)days.getFirst()).get("reason").toString().contains("不参与gap计算"));
    var empty=BidGapService.calculate(List.of(row("2026-09-05",0,100),row("2026-09-06",10,0)),LocalDate.of(2026,9,9));
    assertNull(((Map<?,?>)((Map<?,?>)empty.get("accounts")).get("123")).get("gap"));
  }
  @Test void dingtalkUsesAdjustedPriceAndMarksUnmatchedAccounts(){
    var snapshot=ReportService.mapOf("rows",List.of(Map.of("advertiser_id","123","media_account_name","账户甲","promotion_id","p1","user_name","张三","stat_cost",100,"convert_cnt",10,"active_register",100,"cpa_bid",5)));
    List<Map<String,Object>> rules=List.of(Map.of("name","任务甲","keyword","账户甲","price",1));
    var output=BidTop5Formatter.messages(snapshot,rules,List.of("任务甲"),Map.of("123",Map.of("gap",.5))).getFirst().get("text");
    assertTrue(output.contains("利润出价0.00%"));
    assertTrue(BidTop5Formatter.messages(snapshot,rules,List.of("任务甲"),Map.of()).getFirst().get("text").contains("gap缺失"));
  }
  @Test void exposesLatestHistoricalSettlementUnitPriceForTheAccount(){
    var result=BidGapService.calculate(List.of(pricedRow("2026-08-20",100,10,100),pricedRow("2026-09-05",400,20,100),
        pricedRow("2026-09-07",630,30,100),pricedRow("2026-09-08",999,0,100)),LocalDate.of(2026,9,9));
    var account=(Map<String,Object>)((Map<?,?>)result.get("accounts")).get("123");
    assertEquals(21d,(double)account.get("settlementPrice"),1e-12);assertEquals("2026-09-07",account.get("settlementPriceDate"));
    assertEquals(630d,account.get("settlementCommission"));assertEquals(30d,account.get("settlementCount"));
  }
  @Test void dingtalkInfersGdtTaskFromHistoricalSettlementUnitPrice(){
    var row=Map.<String,Object>of("source_platform","gdt","advertiser_id","123","media_account_id","456","media_account_name","无法识别账户",
        "promotion_id","p1","user_name","张三","stat_cost",100,"convert_cnt",20,"active_register",100,"cpa_bid",10);
    var snapshot=ReportService.mapOf("rows",List.of(row));
    var rules=List.of(Map.<String,Object>of("name","任务甲","keyword","甲账户","price",10),Map.<String,Object>of("name","任务乙","keyword","乙账户","price",30));
    String output=BidTop5Formatter.messages(snapshot,rules,List.of("任务甲"),Map.of("000456.0",Map.of("gap",.2,"settlementPrice",10.01,"settlementPriceDate","2026-09-08"))).getFirst().get("text");
    assertTrue(output.contains("历史结算价反推"));assertFalse(output.contains("暂无匹配计划"));assertFalse(output.contains("gap缺失"));
  }
  @Test void latestDailyReportTaskNameWorksWithoutSettlementAndOverridesAccountKeyword(){
    var calculated=BidGapService.calculate(List.of(taskRow("2026-09-07","任务甲",100),taskRow("2026-09-08","任务乙",20)),LocalDate.of(2026,9,9));
    var account=(Map<?,?>)((Map<?,?>)calculated.get("accounts")).get("123");
    assertEquals("任务乙",account.get("taskName"));assertEquals("2026-09-08",account.get("taskDate"));assertNull(account.get("settlementPrice"));
    var row=Map.<String,Object>of("source_platform","gdt","advertiser_id","123","media_account_name","甲账户",
        "promotion_id","p1","user_name","张三","stat_cost",100,"convert_cnt",20,"active_register",100,"cpa_bid",10);
    var rules=List.of(Map.<String,Object>of("name","任务甲","keyword","甲账户","price",10),Map.<String,Object>of("name","任务乙","keyword","乙账户","price",30));
    String output=BidTop5Formatter.messages(ReportService.mapOf("rows",List.of(row)),rules,List.of("任务乙"),
        Map.of("123",Map.of("gap",.5,"taskName","任务乙","taskDate","2026-09-08"))).getFirst().get("text");
    assertTrue(output.contains("日报任务"));assertFalse(output.contains("暂无匹配计划"));
  }
  @Test void dailyTaskUsesTheSameOptimizerBeforeTheAccountWideTask(){
    var calculated=BidGapService.calculate(List.of(
        Map.of("账户ID","123","日期","2026-09-08","优化师","甲","任务名","任务甲","消耗",10,"预估佣金",0,"结算数",0,"注册数",0),
        Map.of("账户ID","123","日期","2026-09-08","优化师","乙","任务名","任务乙","消耗",20,"预估佣金",0,"结算数",0,"注册数",0)),LocalDate.of(2026,9,9));
    var account=(Map<?,?>)((Map<?,?>)calculated.get("accounts")).get("123");
    assertEquals("任务乙",account.get("taskName"));
    assertEquals("任务甲",((Map<?,?>)((Map<?,?>)account.get("taskByOptimizer")).get("甲")).get("taskName"));
    var row=Map.<String,Object>of("source_platform","gdt","advertiser_id","123","media_account_name","账户","user_name","甲",
        "promotion_id","p1","stat_cost",10,"convert_cnt",1,"active_register",2,"cpa_bid",1);
    var rules=List.of(Map.<String,Object>of("name","任务甲","keyword","不会命中","price",10),Map.<String,Object>of("name","任务乙","keyword","不会命中2","price",20));
    assertTrue(BidTop5Formatter.messages(ReportService.mapOf("rows",List.of(row)),rules,List.of("任务甲"),(Map<String,Object>)calculated.get("accounts")).getFirst().get("text").contains("日报任务"));
  }
  @SuppressWarnings("unchecked")
  @Test void exposesExactTwoDaysPriorWeightedDailyPriceByAccountAndTask(){
    var rows=List.of(
        Map.<String,Object>of("账户ID","123","日期","2026-09-07","任务名","任务甲","消耗",10,"预估佣金",200,"结算数",10,"注册数",100),
        Map.<String,Object>of("账户ID","123","日期","2026-09-07","任务名","任务甲","消耗",20,"预估佣金",600,"结算数",20,"注册数",100),
        Map.<String,Object>of("账户ID","123","日期","2026-09-08","任务名","任务甲","消耗",30,"预估佣金",999,"结算数",1,"注册数",100),
        Map.<String,Object>of("账户ID","456","日期","2026-09-07","任务名","任务甲","消耗",5,"预估佣金",100,"结算数",5,"注册数",50));
    var result=BidGapService.calculate(rows,LocalDate.of(2026,9,9));
    assertEquals("2026-09-07",result.get("priceDate"));
    var account=(Map<String,Object>)((Map<?,?>)result.get("accounts")).get("123");
    var accountPrice=(Map<String,Object>)account.get("dailyPrice");
    assertEquals(800d/30d,(double)accountPrice.get("price"),1e-12);
    assertEquals(800d,accountPrice.get("commission"));assertEquals(30d,accountPrice.get("settlements"));
    var split=(Map<String,Object>)((Map<?,?>)account.get("dailyPricesByTask")).get("任务甲");
    assertEquals(800d/30d,(double)split.get("price"),1e-12);
    var task=(Map<String,Object>)((Map<?,?>)result.get("tasks")).get("任务甲");
    var taskPrice=(Map<String,Object>)task.get("dailyPrice");
    assertEquals(900d/35d,(double)taskPrice.get("price"),1e-12);
    assertEquals(35d/250d,(double)task.get("gap"),1e-12);
  }
  @SuppressWarnings("unchecked")
  @Test void zeroCommissionIsARealDailyPriceButMissingOrZeroSettlementIsExplained(){
    var result=BidGapService.calculate(List.of(
        Map.<String,Object>of("账户ID","zero","日期","2026-09-07","任务名","零佣金","消耗",1,"预估佣金",0,"结算数",4,"注册数",10),
        Map.<String,Object>of("账户ID","empty","日期","2026-09-07","任务名","无结算","消耗",1,"预估佣金",10,"结算数",0,"注册数",10)),LocalDate.of(2026,9,9));
    var accounts=(Map<String,Object>)result.get("accounts");
    var zero=(Map<String,Object>)((Map<?,?>)accounts.get("zero")).get("dailyPrice");
    assertEquals(0d,zero.get("price"));assertNull(zero.get("reason"));
    var empty=(Map<String,Object>)((Map<?,?>)accounts.get("empty")).get("dailyPrice");
    assertNull(empty.get("price"));assertTrue(empty.get("reason").toString().contains("不大于0"));
  }
  @Test void dingtalkUsesExactDailyPriceWhenManualPriceIsBlank(){
    var row=Map.<String,Object>of("source_platform","gdt","advertiser_id","123","media_account_name","账户甲",
        "promotion_id","p1","user_name","张三","stat_cost",100,"convert_cnt",20,"active_register",100,"cpa_bid",10);
    var snapshot=ReportService.mapOf("rows",List.of(row));
    var rules=List.of(Map.<String,Object>of("name","任务甲","keyword","账户甲","price",""));
    var daily=Map.<String,Object>of("date","2026-09-07","price",20);
    var payload=ReportService.mapOf("priceDate","2026-09-07","accounts",Map.of("123",ReportService.mapOf(
        "gap",.5,"dailyPricesByTask",Map.of("任务甲",daily))),"tasks",Map.of());
    String text=BidTop5Formatter.messages(snapshot,rules,List.of("任务甲"),payload).getFirst().get("text");
    assertTrue(text.contains("利润出价80.00%"));assertTrue(text.contains("账户前天日报价"));
    var stale=new LinkedHashMap<>(daily);stale.put("date","2026-09-06");
    payload.put("accounts",Map.of("123",ReportService.mapOf("gap",.5,"dailyPricesByTask",Map.of("任务甲",stale))));
    assertTrue(BidTop5Formatter.messages(snapshot,rules,List.of("任务甲"),payload).getFirst().get("text").contains("单价缺失"));
  }
  @Test void dingtalkKeepsSameAccountOptimizersInTheirOwnDailyTasks(){
    var first=Map.<String,Object>of("source_platform","gdt","advertiser_id","123","media_account_name","账户",
        "promotion_id","p1","user_name","甲","stat_cost",100,"convert_cnt",10,"active_register",20,"cpa_bid",5);
    var second=Map.<String,Object>of("source_platform","gdt","advertiser_id","123","media_account_name","账户",
        "promotion_id","p2","user_name","乙","stat_cost",90,"convert_cnt",10,"active_register",20,"cpa_bid",5);
    var rules=List.of(Map.<String,Object>of("name","任务甲","keyword","不会命中甲","price",10),
        Map.<String,Object>of("name","任务乙","keyword","不会命中乙","price",10));
    var account=ReportService.mapOf("gap",1,"taskByOptimizer",Map.of(
        "甲",Map.of("taskName","任务甲","taskDate","2026-09-08"),
        "乙",Map.of("taskName","任务乙","taskDate","2026-09-08")));
    String text=BidTop5Formatter.messages(ReportService.mapOf("rows",List.of(first,second)),rules,List.of("任务甲","任务乙"),Map.of("123",account)).getFirst().get("text");
    assertTrue(text.contains("【任务甲 TOP5】\n①"));assertTrue(text.contains("甲｜账123｜计p1"));
    assertTrue(text.contains("【任务乙 TOP5】\n①"));assertTrue(text.contains("乙｜账123｜计p2"));
  }
  @Test void dingtalkUsesBidAndReturnRatioForOtherwiseUnmatchedPlans(){
    var row=Map.<String,Object>of("source_platform","byte","advertiser_id","new","media_account_name","未知账户",
        "promotion_id","p1","user_name","甲","stat_cost",981.68,"convert_cnt",11,"active_register",195,"cpa_bid",12);
    var rules=List.of(Map.<String,Object>of("name","任务甲","keyword","不会命中甲","price",""),
        Map.<String,Object>of("name","任务乙","keyword","不会命中乙","price",""));
    var payload=ReportService.mapOf("priceDate","2026-09-08","accounts",Map.of("new",Map.of("gap",.888)),"tasks",Map.of(
        "任务甲",ReportService.mapOf("gap",.888,"dailyPrice",Map.of("date","2026-09-08","price",1)),
        "任务乙",ReportService.mapOf("gap",.843,"dailyPrice",Map.of("date","2026-09-08","price",.24))));
    String text=BidTop5Formatter.messages(ReportService.mapOf("rows",List.of(row)),rules,List.of("任务甲","任务乙"),payload).getFirst().get("text");
    assertTrue(text.contains("【任务甲 TOP5】\n①"));assertTrue(text.contains("出价回传估算任务"));
    assertTrue(text.contains("【任务乙 TOP5】\n暂无匹配计划"));
  }
}
