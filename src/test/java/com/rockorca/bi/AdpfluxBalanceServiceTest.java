package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AdpfluxBalanceServiceTest {
  @Test
  void mapsAdvertiserListBalanceWithoutUnitConversion() {
    AdpfluxBalanceService service = new AdpfluxBalanceService(null, null, null, null);

    Map<String, Object> row = service.mapRow(ReportService.mapOf(
        "advertiser_id", "7672669735099973652",
        "advertiser_name", "厦门云联-18",
        "company_ex_id", "70017864344226243679",
        "balance", 10.16));

    assertEquals("7672669735099973652", row.get("advertiserId"));
    assertEquals("厦门云联-18", row.get("advertiserName"));
    assertEquals("70017864344226243679", row.get("companyExId"));
    assertEquals(10.16, row.get("balance"));
  }
}
