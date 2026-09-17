package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

class BidEndedWarningServiceTest {
  final BidHistoryStore history=mock(BidHistoryStore.class);
  final BidServerSyncStore store=mock(BidServerSyncStore.class);
  final BidCredentialCipher cipher=mock(BidCredentialCipher.class);
  final BidServerSyncService sync=mock(BidServerSyncService.class);
  final BidEndedWarningService service=new BidEndedWarningService(history,store,cipher,sync);
  final LocalDate today=LocalDate.now(ReportService.BEIJING);
  Map<String,Object> row(String id,int conversions){return Map.of("promotion_id",id,"promotion_create_time",today.minusDays(4).toString(),"cpa_bid",10,"stat_cost",100,"convert_cnt",conversions);}
  @BeforeEach void setup()throws Exception{
    when(sync.allowed(7)).thenReturn(true);when(store.get(7)).thenReturn(Map.of("credential","encrypted","credentialRevision","v1"));
    when(cipher.decrypt(7,"encrypted")).thenReturn("private-cookie");
  }
  @AfterEach void close(){service.close();}
  Map<String,Object> awaitResult()throws Exception{
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
    while(System.nanoTime()<deadline){var result=service.read(7,false);if(!"running".equals(result.get("state")))return result;Thread.sleep(10);}
    throw new AssertionError("Verification did not finish");
  }
  @Test void backfilledSixConversionsRemoveOldWarningAndUnavailableRowsAreNotAssumedZero()throws Exception{
    when(history.readEndedPlans(7,today)).thenReturn(List.of(row("1",3),row("2",2),row("3",0)));
    when(sync.collectPlanTotals(anyMap(),eq("private-cookie"),anyList(),eq(today))).thenReturn(List.of(row("1",6),row("2",5)));
    var result=awaitResult();assertEquals("ready",result.get("state"));assertEquals(1,result.get("count"));assertEquals(1,result.get("unverifiedCount"));
    var warning=(Map<?,?>)((List<?>)result.get("rows")).getFirst();assertEquals("2",warning.get("promotion_id"));assertEquals(1,warning.get("shortfall"));
    assertEquals(true,warning.get("verified"));assertFalse(warning.containsKey("archive_complete"));assertEquals(today.toString(),warning.get("last_date"));
    service.read(7,false);verify(sync,times(1)).collectPlanTotals(anyMap(),anyString(),anyList(),any());
  }
  @Test void concurrentViewersShareJobAndErrorsDoNotReturnOldRowsOrSecrets()throws Exception{
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(history.readEndedPlans(7,today)).thenReturn(List.of(row("1",3)));
    when(sync.collectPlanTotals(anyMap(),anyString(),anyList(),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));throw new IllegalArgumentException("private-cookie");});
    service.read(7,false);assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals("running",service.read(7,true).get("state"));release.countDown();
    var result=awaitResult();assertEquals("error",result.get("state"));assertEquals(List.of(),result.get("rows"));assertFalse(result.toString().contains("private-cookie"));
    verify(sync,times(1)).collectPlanTotals(anyMap(),anyString(),anyList(),any());
  }
}
