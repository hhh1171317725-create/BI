package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClickflareRevenueServiceTest {
  @Test
  void scheduledSyncRefreshesYesterdayAndToday() {
    LocalDate today = LocalDate.of(2026, 8, 22);

    assertEquals(
        List.of(LocalDate.of(2026, 8, 21), today),
        ClickflareRevenueService.scheduledDates(today));
  }

  @Test
  @SuppressWarnings("unchecked")
  void normalRevenueRequestReadsDatabaseWithoutCallingCredentialsOrUpstream() {
    RuntimeConfig config = mock(RuntimeConfig.class);
    ClickflareRevenueRepository repository = mock(ClickflareRevenueRepository.class);
    List<Map<String, Object>> rows = List.of(ReportService.mapOf(
        "campaignId", "1001", "campaignName", "测试活动", "revenue", 12.5));
    when(repository.readDate("2026-08-20")).thenReturn(rows);
    when(repository.latestSyncTime("2026-08-20")).thenReturn("2026-08-20T10:00:00");
    ClickflareRevenueService service =
        new ClickflareRevenueService(config, new ObjectMapper(), repository);

    Map<String, Object> result = service.revenue("2026-08-20", false);

    assertEquals("mysql", result.get("source"));
    assertEquals(rows, result.get("rows"));
    assertEquals("2026-08-20T10:00:00", result.get("cachedAt"));
    verify(repository).readDate("2026-08-20");
    verify(repository).latestSyncTime("2026-08-20");
    verifyNoInteractions(config);
  }

  @Test
  void rejectsInvalidRevenueDateBeforeDatabaseQuery() {
    ClickflareRevenueRepository repository = mock(ClickflareRevenueRepository.class);
    ClickflareRevenueService service = new ClickflareRevenueService(
        mock(RuntimeConfig.class), new ObjectMapper(), repository);

    assertThrows(IllegalArgumentException.class, () -> service.revenue("not-a-date", false));
    verifyNoInteractions(repository);
  }

  @Test
  void rangeRevenueReadsAggregatedRowsFromDatabase() {
    ClickflareRevenueRepository repository = mock(ClickflareRevenueRepository.class);
    List<Map<String, Object>> rows = List.of(ReportService.mapOf(
        "campaignId", "1001", "campaignName", "测试活动", "revenue", 25d));
    when(repository.readRange("2026-08-01", "2026-08-20")).thenReturn(rows);
    when(repository.latestSyncTime("2026-08-01", "2026-08-20"))
        .thenReturn("2026-08-20T10:00:00");
    ClickflareRevenueService service = new ClickflareRevenueService(
        mock(RuntimeConfig.class), new ObjectMapper(), repository);

    Map<String, Object> result = service.revenueRange("2026-08-01", "2026-08-20");

    assertEquals("2026-08-01", result.get("start"));
    assertEquals("2026-08-20", result.get("end"));
    assertEquals(rows, result.get("rows"));
    verify(repository).readRange("2026-08-01", "2026-08-20");
  }

  @Test
  void rejectsReversedRevenueRange() {
    ClickflareRevenueRepository repository = mock(ClickflareRevenueRepository.class);
    ClickflareRevenueService service = new ClickflareRevenueService(
        mock(RuntimeConfig.class), new ObjectMapper(), repository);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.revenueRange("2026-08-20", "2026-08-01"));
    verifyNoInteractions(repository);
  }
}
