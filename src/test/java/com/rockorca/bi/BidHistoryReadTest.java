package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class BidHistoryReadTest {
  @Test void endedWarningsAggregateAcrossDatesWithinSharedOwnerAndFullPlanIdentity()throws Exception{
    var reports=mock(ReportRepository.class);var connection=mock(Connection.class);
    var statement=mock(PreparedStatement.class);var result=mock(ResultSet.class);
    when(reports.openConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true,false);
    when(result.getString("payload")).thenReturn("{\"promotion_id\":\"old-plan\",\"promotion_create_time\":\"2026-09-12\",\"cpa_bid\":10}");
    when(result.getDouble("total_cost")).thenReturn(100.0);when(result.getDouble("total_conversions")).thenReturn(5.0);
    when(result.getString("first_date")).thenReturn("2026-09-12");when(result.getString("last_date")).thenReturn("2026-09-15");when(result.getInt("days")).thenReturn(4);
    var store=new BidHistoryStore(reports,new ObjectMapper());ReflectionTestUtils.setField(store,"initialized",true);
    var rows=store.readEndedWarnings(7,LocalDate.parse("2026-09-16"));assertEquals(1,rows.size());assertEquals(1,rows.getFirst().get("shortfall"));
    var sql=ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());
    assertTrue(sql.getValue().contains("GROUP BY user_id,source_platform,media_account_id,promotion_id"));
    assertTrue(sql.getValue().contains("h.report_date=a.last_date"));assertTrue(sql.getValue().contains("HAVING MIN(COALESCE("));
    verify(statement).setLong(1,7);verify(statement).setString(2,"2026-09-16");verify(statement).setFetchSize(Integer.MIN_VALUE);
  }
  @Test void conversionsReadPreservesIdentityAndUsesOneBoundedStreamingQuery() throws Exception {
    var reports=mock(ReportRepository.class);
    var connection=mock(Connection.class);
    var statement=mock(PreparedStatement.class);
    var result=mock(ResultSet.class);
    when(reports.openConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true,false);
    when(result.getString("report_date")).thenReturn("2026-09-13");
    when(result.getString("payload")).thenReturn("""
        {"promotion_id":"123","media_account_id":"456","advertiser_id":"789","source_platform":"gdt","platform_text":"广点通","convert_cnt":6,"stat_cost":72.5}
        """);
    var store=new BidHistoryStore(reports,new ObjectMapper());
    ReflectionTestUtils.setField(store,"initialized",true);
    var rows=store.readConversions(7,LocalDate.parse("2026-09-10"),LocalDate.parse("2026-09-13"));
    assertEquals("789",rows.getFirst().get("advertiser_id"));
    assertEquals("广点通",rows.getFirst().get("platform_text"));
    assertEquals(6,rows.getFirst().get("convert_cnt"));
    assertEquals(72.5,((Number)rows.getFirst().get("stat_cost")).doubleValue());
    assertEquals("2026-09-13",rows.getFirst().get("report_date"));
    var sql=ArgumentCaptor.forClass(String.class);
    verify(connection,times(1)).prepareStatement(sql.capture());
    assertTrue(sql.getValue().contains("JSON_OBJECT"));
    assertTrue(sql.getValue().contains("stat_cost"));
    assertTrue(sql.getValue().contains("LIMIT 200001"));
    assertFalse(sql.getValue().contains("COUNT(*)"));
    verify(statement).setFetchSize(Integer.MIN_VALUE);
    verify(statement).setLong(1,7);
    verify(statement).setString(2,"2026-09-10");
    verify(statement).setString(3,"2026-09-13");
  }
}
