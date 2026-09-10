package com.rockorca.bi;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BidAccountReferenceCacheTest {
  @Test void reusesSavedReferencesAfterRestartAndDoesNotReadDailyRows(){
    var repository=mock(ReportRepository.class);var reports=mock(ReportService.class);var store=mock(BidAccountReferenceStore.class);
    when(store.revision()).thenReturn("v1:1:1");
    var saved=Map.<String,Object>of("anchor","2026-09-10","accounts",Map.of("123",Map.of("taskName","任务甲")));
    when(store.read("2026-09-10","v1:1:1")).thenReturn(saved);
    var service=new BidGapService(repository,reports,store);
    assertSame(saved,service.load("2026-09-10"));assertSame(saved,service.load("2026-09-10"));
    assertSame(saved,new BidGapService(repository,reports,store).load("2026-09-10"));
    verifyNoInteractions(repository,reports);verify(store,times(2)).read("2026-09-10","v1:1:1");
  }

  @Test void updatesOnImportAndSeparatesStatisticalDates(){
    var repository=mock(ReportRepository.class);var reports=mock(ReportService.class);var store=mock(BidAccountReferenceStore.class);
    when(store.read(anyString(),anyString())).thenReturn(null);
    when(store.revision()).thenReturn("v1:1:1");when(reports.buildDhhAccountRows(anyList())).thenReturn(List.of());
    var service=new BidGapService(repository,reports,store);
    service.load("2026-09-10");service.load("2026-09-10");
    verify(repository,times(1)).readDhhRows("2026-08-11","2026-09-09","");
    when(store.revision()).thenReturn("v1:2:2");
    var latest=service.load("2026-09-10");
    assertEquals("v1:2:2",latest.get("sourceRevision"));assertNotNull(latest.get("preparedAt"));
    verify(repository,times(2)).readDhhRows("2026-08-11","2026-09-09","");
    verify(store).save(eq("2026-09-10"),eq("v1:2:2"),same(latest));
    assertEquals("2026-09-07",service.load("2026-09-09").get("priceDate"));
  }

  @Test void retriesIfDailyImportCommitsDuringCalculation(){
    var repository=mock(ReportRepository.class);var reports=mock(ReportService.class);var store=mock(BidAccountReferenceStore.class);
    when(store.read(anyString(),anyString())).thenReturn(null);
    when(store.revision()).thenReturn("old","new","new","new");
    when(reports.buildDhhAccountRows(anyList())).thenReturn(List.of());
    var data=new BidGapService(repository,reports,store).load("2026-09-10");
    verify(store,never()).save(anyString(),eq("old"),anyMap());
    verify(store).save(eq("2026-09-10"),eq("new"),same(data));
  }

  @Test void concurrentRequestsCalculateOnlyOnce() throws Exception {
    var repository=mock(ReportRepository.class);var reports=mock(ReportService.class);var store=mock(BidAccountReferenceStore.class);
    when(store.read(anyString(),anyString())).thenReturn(null);
    when(store.revision()).thenReturn("v1:1:1");when(reports.buildDhhAccountRows(anyList())).thenReturn(List.of());
    var service=new BidGapService(repository,reports,store);
    try(var executor=Executors.newFixedThreadPool(4)){
      var jobs=new ArrayList<Callable<Map<String,Object>>>();for(int i=0;i<12;i++)jobs.add(()->service.load("2026-09-10"));
      var results=executor.invokeAll(jobs);var first=results.getFirst().get();
      for(var result:results)assertSame(first,result.get());
    }
    verify(repository,times(1)).readDhhRows("2026-08-11","2026-09-09","");
  }
}
