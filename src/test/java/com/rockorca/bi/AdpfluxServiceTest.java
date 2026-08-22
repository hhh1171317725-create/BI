package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdpfluxServiceTest {
  @Test
  void analysisAggregatesAccountsDatesAndLatestBalances() {
    AdpfluxService service = new AdpfluxService(null, null, null, null);
    List<Map<String, Object>> rows = List.of(
        row("2026-08-19", "1001", "账户A", 10, 100, 2, 5),
        row("2026-08-20", "1001", "账户A", 5, 40, 1, 8),
        row("2026-08-20", "1002", "账户B", 0, 10, 0, 3));

    Map<String, Object> analysis =
        service.buildAnalysis(rows, "2026-08-19", "2026-08-20", "2026-08-20T12:00:00");
    Map<?, ?> summary = (Map<?, ?>) analysis.get("summary");
    List<?> accounts = (List<?>) analysis.get("by_account");
    List<?> dates = (List<?>) analysis.get("by_date");

    assertEquals(2, summary.get("accounts"));
    assertEquals(1L, summary.get("spendingAccounts"));
    assertEquals(15d, summary.get("totalSpend"));
    assertEquals(11d, summary.get("totalBalance"));
    assertEquals(150L, summary.get("clicks"));
    assertEquals(3L, summary.get("conversions"));
    assertEquals(5d, summary.get("cpa"));
    assertEquals(2, accounts.size());
    assertEquals("2026-08-20", ((Map<?, ?>) dates.getFirst()).get("date"));
  }

  @Test
  void currentBalanceOnlyOverridesEachAccountsLatestSelectedDate() {
    List<Map<String, Object>> rows = List.of(
        row("2026-08-19", "1001", "账户A", 10, 100, 2, 5),
        row("2026-08-20", "1001", "账户A", 5, 40, 1, 8),
        row("2026-08-20", "1002", "账户B", 0, 10, 0, 3));

    List<Map<String, Object>> merged =
        AdpfluxService.applyCurrentBalances(rows, Map.of("1001", 12.34, "1002", 6.78));

    assertEquals(5d, merged.get(0).get("balance"));
    assertEquals(12.34, merged.get(1).get("balance"));
    assertEquals(6.78, merged.get(2).get("balance"));
  }

  @Test
  void upstreamMappingKeepsBoardValuesWithoutUnitConversion() {
    AdpfluxUpstreamService upstream = new AdpfluxUpstreamService(null, null, null);
    Map<String, Object> mapped = upstream.mapRow(
        LocalDate.of(2026, 8, 20),
        ReportService.mapOf(
            "advertiser_id", "7672681729764720661",
            "advertiser_name", "厦门云联-5",
            "balance", 9.6,
            "total_spend", 5.76,
            "clicks", 36,
            "conversions", 1,
            "cpa", 5.76,
            "cvr", 2.78,
            "status", 1,
            "status_raw", "STATUS_ENABLE",
            "timezone", "UTC+00:00"));

    assertEquals("2026-08-20", mapped.get("date"));
    assertEquals(5.76, mapped.get("totalSpend"));
    assertEquals(36d, mapped.get("clicks"));
    assertEquals("UTC+00:00", mapped.get("timezone"));
  }

  private static Map<String, Object> row(
      String date,
      String id,
      String name,
      double spend,
      double clicks,
      double conversions,
      double balance) {
    return ReportService.mapOf(
        "date", date,
        "advertiserId", id,
        "advertiserName", name,
        "balance", balance,
        "billedCost", 0,
        "cashSpend", 0,
        "voucherSpend", 0,
        "totalSpend", spend,
        "clicks", clicks,
        "conversions", conversions,
        "currency", "USD",
        "status", 1,
        "statusRaw", "STATUS_ENABLE",
        "timezone", "UTC+00:00");
  }
}
