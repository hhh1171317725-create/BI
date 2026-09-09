package com.rockorca.bi;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BidGapServiceTest {
  @Test void loadQueriesTheThreeDaysBeforeYesterdayAcrossMonthBoundary(){
    var repository=org.mockito.Mockito.mock(ReportRepository.class);
    var reports=org.mockito.Mockito.mock(ReportService.class);
    org.mockito.Mockito.when(repository.readDhhRows("2026-08-29","2026-08-31","")).thenReturn(List.of());
    org.mockito.Mockito.when(reports.buildDhhAccountRows(List.of())).thenReturn(List.of());
    var result=new BidGapService(repository,reports).load("2026-09-02");
    org.mockito.Mockito.verify(repository).readDhhRows("2026-08-29","2026-08-31","");
    assertEquals("2026-09-02",result.get("anchor"));
    assertEquals("2026-08-29",result.get("start"));
    assertEquals("2026-08-31",result.get("end"));
  }
  private Map<String,Object> row(String date,double settled,double registered){return Map.of("账户ID","123", "日期",date,"结算数",settled,"注册数",registered);}
  @SuppressWarnings("unchecked")
  @Test void averagesDailyRatiosAfterCombiningSameDayRows(){
    var result=BidGapService.calculate(List.of(row("2026-09-05",40,100),row("2026-09-05",10,100),row("2026-09-06",90,100),row("2026-09-07",60,100),row("2026-09-08",900,100),row("2026-09-09",900,100),row("2026-09-04",900,100)),LocalDate.of(2026,9,9));
    var account=(Map<String,Object>)((Map<?,?>)result.get("accounts")).get("123");
    assertEquals((.25+.9+.6)/3,(double)account.get("gap"),1e-12);
    assertEquals(3,account.get("validDays"));
    assertEquals("2026-09-05",result.get("start"));assertEquals("2026-09-07",result.get("end"));
  }
  @Test void missingAndZeroRegistrationDaysAreExcludedButZeroSettlementIsValid(){
    var result=BidGapService.calculate(List.of(row("2026-09-05",0,100),row("2026-09-06",10,0)),LocalDate.of(2026,9,9));
    var account=(Map<?,?>)((Map<?,?>)result.get("accounts")).get("123");
    assertEquals(0.0,account.get("gap"));assertEquals(1,account.get("validDays"));
    var empty=BidGapService.calculate(List.of(row("2026-09-05",10,0)),LocalDate.of(2026,9,9));
    assertNull(((Map<?,?>)((Map<?,?>)empty.get("accounts")).get("123")).get("gap"));
  }
  @Test void dingtalkUsesAdjustedPriceAndMarksUnmatchedAccounts(){
    var snapshot=ReportService.mapOf("rows",List.of(Map.of("advertiser_id","123","media_account_name","账户甲","promotion_id","p1","user_name","张三","stat_cost",100,"convert_cnt",10,"active_register",100,"cpa_bid",5)));
    List<Map<String,Object>> rules=List.of(Map.of("name","任务甲","keyword","账户甲","price",1));
    var output=BidTop5Formatter.messages(snapshot,rules,List.of("任务甲"),Map.of("123",Map.of("gap",.5))).getFirst().get("text");
    assertTrue(output.contains("利润出价0.00%"));
    assertTrue(BidTop5Formatter.messages(snapshot,rules,List.of("任务甲"),Map.of()).getFirst().get("text").contains("gap缺失"));
  }
}
