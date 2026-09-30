package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JdAnalysisViewTest {
  @Test void eachViewMatchesFullReportIncludingItsDrilldownAndSummary() {
    var service = new ReportService(null, null, null, new ObjectMapper());
    var rows = DailyAnalysisBenchmarkTest.rows(800);
    rows.getFirst().put("优化师", "未知");
    for (boolean exclude : List.of(true, false)) {
      var full = service.buildJdAnalysis(rows, "2026-09-01", "2026-09-02", exclude, "v1");
      for (String view : List.of("by_optimizer", "by_date", "by_media", "by_account", "by_promoter")) {
        var partial = service.buildJdAnalysis(rows, "2026-09-01", "2026-09-02", exclude, "v1", view);
        Set<String> expected = new HashSet<>(Set.of("cachedAt", "nextScheduledRefreshAt", "rows", "range", "excludeUnknownOptimizer", "summary", view));
        if (!view.equals("by_date")) expected.add(view + "_date");
        assertEquals(expected, partial.keySet());
        for (String key : expected) assertEquals(full.get(key), partial.get(key), view + ":" + key);
      }
    }
  }

  @Test void cacheSeparatesViewsFiltersAndSyncRevision() {
    var repository = mock(ReportRepository.class);
    var service = new ReportService(repository, null, null, new ObjectMapper());
    when(repository.latestSyncTime("jd")).thenReturn("v1");
    when(repository.readJdRows(anyString(), anyString(), anyString())).thenReturn(DailyAnalysisBenchmarkTest.rows(10));
    var first = service.analyzeJd("2026-09-01", "2026-09-02", true, "", "by_optimizer");
    assertSame(first, service.analyzeJd("2026-09-01", "2026-09-02", true, "", "by_optimizer"));
    verify(repository, times(1)).readJdRows(anyString(), anyString(), anyString());
    assertFalse(service.analyzeJd("2026-09-01", "2026-09-02", true, "", "by_account").containsKey("by_optimizer"));
    assertNotSame(first, service.analyzeJd("2026-09-01", "2026-09-02", false, "", "by_optimizer"));
    service.analyzeJd("2026-09-01", "2026-09-02", true, "123", "by_optimizer");
    verify(repository).readJdRows("2026-09-01", "2026-09-02", "123");
    when(repository.latestSyncTime("jd")).thenReturn("v2");
    assertNotSame(first, service.analyzeJd("2026-09-01", "2026-09-02", true, "", "by_optimizer"));
    assertTrue(service.analyzeJd("2026-09-01", "2026-09-02", true, "").containsKey("by_account"));
    assertThrows(IllegalArgumentException.class, () -> service.analyzeJd("", "", true, "", "invalid"));
  }
}
