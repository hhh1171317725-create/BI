package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AfsMonitorServiceTest {
  @Test
  void mapsVerifiedOverviewFields() {
    AfsMonitorService service = new AfsMonitorService(null, null, null, null, null);

    Map<String, Object> row = service.mapOverview(
        "2026-08-22", "1775788078", "1751233588", ReportService.mapOf(
            "lp1_page_view_count", 860,
            "lp1_related_search_load_result_count", 689,
            "lp1_related_search_loaded_count", 689,
            "lp1_related_search_no_fill_count", 0,
            "lp1_related_search_unknown_count", 171,
            "lp1_related_search_render_success_count", 689,
            "page_view_count", 123,
            "afs_render_success_count", 110,
            "afs_ad_click_count", 63));

    assertEquals("1775788078", row.get("channelId"));
    assertEquals("1751233588", row.get("styleId"));
    assertEquals(860L, row.get("lp1PageViews"));
    assertEquals(110L, row.get("afsRenderSuccess"));
    assertEquals(63L, row.get("afsAdClicks"));
  }

  @Test
  void aggregatesCountsAndRecalculatesRates() {
    List<Map<String, Object>> rows = List.of(
        row(100, 80, 50, 20),
        row(200, 160, 100, 40));

    Map<String, Object> summary = AfsMonitorService.aggregate(rows);

    assertEquals(300L, summary.get("lp1PageViews"));
    assertEquals(240L, summary.get("relatedSearchLoadResults"));
    assertEquals(150L, summary.get("afsRenderSuccess"));
    assertEquals(60L, summary.get("afsAdClicks"));
    assertEquals(80d, summary.get("relatedSearchCallbackRate"));
    assertEquals(40d, summary.get("adClickRate"));
  }

  private static Map<String, Object> row(
      long lp1Views, long searchLoads, long renders, long clicks) {
    return ReportService.mapOf(
        "lp1PageViews", lp1Views,
        "relatedSearchLoadResults", searchLoads,
        "relatedSearchLoaded", searchLoads,
        "relatedSearchNoFill", 0,
        "relatedSearchUnknown", lp1Views - searchLoads,
        "relatedSearchRenderSuccess", searchLoads,
        "pageViews", renders,
        "afsRenderSuccess", renders,
        "afsAdClicks", clicks);
  }
}
