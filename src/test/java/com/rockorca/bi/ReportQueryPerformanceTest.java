package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReportQueryPerformanceTest {
  @Test void concurrentViewersShareOneAnalysisAndNewSyncInvalidatesIt() throws Exception {
    for (String report : List.of("dhh", "jd")) {
      var repository = mock(ReportRepository.class);
      var service = new ReportService(repository, null, null, new ObjectMapper());
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      when(repository.latestSyncTime(report)).thenReturn("revision-1");
      org.mockito.stubbing.Answer<List<Map<String,Object>>> read = invocation -> {
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return List.of();
      };
      when(repository.readDhhRows(anyString(),anyString(),anyString(),anyString(),anyBoolean())).thenAnswer(read);
      when(repository.readJdRows(anyString(),anyString(),anyString())).thenAnswer(read);
      java.util.concurrent.Callable<Map<String,Object>> query = () -> report.equals("dhh")
          ? service.analyzeDhh("2026-09-01","2026-09-07","","by_optimizer")
          : service.analyzeJd("2026-09-01","2026-09-07",true,"");
      try (var executor = Executors.newFixedThreadPool(8)) {
        var first = executor.submit(query);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var others = new java.util.ArrayList<java.util.concurrent.Future<Map<String,Object>>>();
        for (int i=0;i<7;i++) others.add(executor.submit(query));
        release.countDown();
        var result = first.get(5,TimeUnit.SECONDS);
        for (var other : others) assertSame(result,other.get(5,TimeUnit.SECONDS));
        if (report.equals("dhh")) verify(repository,times(1)).readDhhRows(anyString(),anyString(),anyString(),anyString(),anyBoolean());
        else verify(repository,times(1)).readJdRows(anyString(),anyString(),anyString());
        when(repository.latestSyncTime(report)).thenReturn("revision-2");
        assertNotSame(result,query.call());
      } finally { release.countDown(); }
    }
  }

  @Test void fillingCacheEvictsOldestInsteadOfAllRecentReports() {
    var repository=mock(ReportRepository.class);
    when(repository.latestSyncTime("dhh")).thenReturn("revision-1");
    when(repository.readDhhRows(anyString(),anyString(),anyString(),anyString(),anyBoolean())).thenReturn(List.of());
    var service=new ReportService(repository,null,null,new ObjectMapper());
    for(int i=0;i<12;i++) service.analyzeDhh("2026-09-01","2026-09-07",String.valueOf(i),"by_optimizer");
    var cache=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(service,"dhhAnalysisCache");
    var previousKeys=new java.util.HashSet<>(cache.keySet());
    service.analyzeDhh("2026-09-01","2026-09-07","extra","by_optimizer");
    previousKeys.retainAll(cache.keySet());
    assertEquals(12,cache.size());
    assertTrue(previousKeys.size()>=11,"The thirteenth range must not flush all recent ranges");
  }
}
