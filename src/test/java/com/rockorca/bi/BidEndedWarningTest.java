package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class BidEndedWarningTest {
  private final LocalDate today=LocalDate.parse("2026-09-16");
  private Map<String,Object> warning(String created,double cost,double conversions){
    return BidEndedWarning.evaluate(Map.of("promotion_create_time",created,"cpa_bid",10),today,cost,conversions,"2026-09-12","2026-09-15",4);
  }
  @Test void expiredPlansRetainTheirShortfall(){
    var row=warning("2026-09-12 08:30:00",100,5);
    assertEquals(1,row.get("shortfall"));assertEquals("2026-09-15",row.get("period_end"));
    assertEquals(true,row.get("archive_complete"));assertEquals(72.0,row.get("warning_threshold"));
    assertNotNull(warning("2026-08-01",100,0)); // No rolling lookback drops old warnings.
  }
  @Test void finalDayRemainsInActiveWarningsAndLaterPlansAreExcluded(){
    assertNull(warning("2026-09-13",100,5));assertNull(warning("2026-09-14",100,5));
    assertNull(warning("",100,5));assertNull(warning("invalid",100,5));
  }
  @Test void thresholdIsStrictAndSixConversionsClearsWarning(){
    assertNull(warning("2026-09-12",72,5));assertNull(warning("2026-09-12",71.99,0));
    assertNotNull(warning("2026-09-12",72.01,5));
    assertNull(warning("2026-09-12",100,6));assertNull(warning("2026-09-12",100,7));
    assertNull(warning("2026-09-12",100,Double.NaN));
    assertNull(BidEndedWarning.evaluate(Map.of("promotion_create_time","2026-09-12"),today,100,5,"2026-09-12","2026-09-15",4));
  }
  @Test void missingArchiveIsDisclosedRatherThanClaimingFinalCounts(){
    var row=BidEndedWarning.evaluate(Map.of("promotion_create_time","2026-09-12","cpa_bid",10),today,100,4,"2026-09-12","2026-09-14",3);
    assertEquals(false,row.get("archive_complete"));assertEquals(2,row.get("shortfall"));
    assertEquals(false,BidEndedWarning.evaluate(Map.of("promotion_create_time","2026-09-12","cpa_bid",10),today,100,4,"2026-09-12","2026-09-15",3).get("archive_complete"));
  }
  @Test void timestampsUseBeijingCalendarDate(){
    long timestamp=LocalDate.parse("2026-09-12").atStartOfDay(ReportService.BEIJING).toEpochSecond();
    assertNotNull(warning(Long.toString(timestamp),100,5));assertNotNull(warning(Long.toString(timestamp*1000),100,5));
  }
}
