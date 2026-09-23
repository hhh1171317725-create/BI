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
  final java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
  final BidEndedWarningService service=new BidEndedWarningService(history,store,cipher,sync,clock::get);
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
    when(sync.collectPlanTotals(anyMap(),eq("private-cookie"),anyList(),eq(today),any(),any())).thenReturn(List.of(row("1",6),row("2",5)));
    var result=awaitResult();assertEquals("ready",result.get("state"));assertEquals(1,result.get("count"));assertEquals(1,result.get("unverifiedCount"));
    var warning=(Map<?,?>)((List<?>)result.get("rows")).getFirst();assertEquals("2",warning.get("promotion_id"));assertEquals(1,warning.get("shortfall"));
    assertEquals(true,warning.get("verified"));assertFalse(warning.containsKey("archive_complete"));assertEquals(today.toString(),warning.get("last_date"));
    service.read(7,false);verify(sync,times(1)).collectPlanTotals(anyMap(),anyString(),anyList(),any(),any(),any());
  }
  @Test void concurrentViewersShareJobAndErrorsDoNotReturnOldRowsOrSecrets()throws Exception{
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(history.readEndedPlans(7,today)).thenReturn(List.of(row("1",3)));
    when(sync.collectPlanTotals(anyMap(),anyString(),anyList(),any(),any(),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));throw new IllegalArgumentException("private-cookie");});
    service.read(7,false);assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals("running",service.read(7,true).get("state"));release.countDown();
    var result=awaitResult();assertEquals("error",result.get("state"));assertEquals(List.of(),result.get("rows"));assertFalse(result.toString().contains("private-cookie"));
    verify(sync,times(1)).collectPlanTotals(anyMap(),anyString(),anyList(),any(),any(),any());
  }
  @Test void verificationLongerThanCacheTtlStillDeliversResultAndRetainsItForFiveMinutes()throws Exception{
    when(history.readEndedPlans(7,today)).thenReturn(List.of(row("1",3)));
    when(sync.collectPlanTotals(anyMap(),anyString(),anyList(),any(),any(),any())).thenAnswer(call->{
      clock.addAndGet(360_000);return List.of(row("1",3));
    });
    var result=awaitResult();assertEquals("ready",result.get("state"));assertEquals(1,result.get("count"));
    clock.addAndGet(299_999);assertSame(result,service.read(7,false));
    verify(sync,times(1)).collectPlanTotals(anyMap(),anyString(),anyList(),any(),any(),any());
    clock.incrementAndGet();awaitResult();
    verify(sync,times(2)).collectPlanTotals(anyMap(),anyString(),anyList(),any(),any(),any());
  }
  @Test void runningResponseShowsProgressWithoutStartingAnotherVerification()throws Exception{
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(history.readEndedPlans(7,today)).thenReturn(List.of(row("1",3)));
    when(sync.collectPlanTotals(anyMap(),anyString(),anyList(),any(),any(),any())).thenAnswer(call->{
      call.<java.util.function.Consumer<String>>getArgument(4).accept("字节 · 第 1 / 2 组 · 已读取 100 / 500 条");
      entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return List.of(row("1",3));
    });
    service.read(7,false);assertTrue(entered.await(3,TimeUnit.SECONDS));
    var running=service.read(7,false);assertEquals("running",running.get("state"));
    assertTrue(running.get("progress").toString().contains("100 / 500"));assertNotNull(running.get("startedAt"));
    release.countDown();assertEquals("ready",awaitResult().get("state"));
    verify(sync,times(1)).collectPlanTotals(anyMap(),anyString(),anyList(),any(),any(),any());
  }
  @Test void completedPlansArePublishedWhileOtherPagesRunAndDiscardedIfVerificationFails()throws Exception{
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(history.readEndedPlans(7,today)).thenReturn(List.of(row("1",3),row("2",6)));
    when(sync.collectPlanTotals(anyMap(),anyString(),anyList(),any(),any(),any())).thenAnswer(call->{
      call.<java.util.function.Consumer<List<Map<String,Object>>>>getArgument(5).accept(List.of(row("1",3),row("2",6)));
      call.<java.util.function.Consumer<String>>getArgument(4).accept("next page");
      entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));throw new IllegalArgumentException("page failed");
    });
    service.read(7,false);assertTrue(entered.await(3,TimeUnit.SECONDS));
    var partial=service.read(7,false);assertEquals("running",partial.get("state"));assertEquals(2,partial.get("checkedCount"));
    var warnings=(List<?>)partial.get("rows");assertEquals(1,warnings.size());assertEquals(true,((Map<?,?>)warnings.getFirst()).get("verified"));
    release.countDown();var failed=awaitResult();assertEquals("error",failed.get("state"));assertEquals(List.of(),failed.get("rows"));
  }
}
