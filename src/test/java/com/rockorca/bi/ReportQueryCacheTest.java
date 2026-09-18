package com.rockorca.bi;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportQueryCacheTest {
  @Test void lowActivityReusesRowsAndRefreshesWhenDataOrFiltersChange(){
    var repository=mock(ReportRepository.class);var service=new JdLowActivityService(repository,null,null);
    when(repository.latestSyncTime("jd_low_activity")).thenReturn("2026-09-17T12:00:00.001000");
    when(repository.readJdLowActivityRows(anyString(),anyString(),anyString(),anyString())).thenReturn(List.of());
    var first=service.analyze("2026-09-01","2026-09-16","123","task");
    assertSame(first,service.analyze("2026-09-01","2026-09-16","123","task"));
    verify(repository,times(1)).readJdLowActivityRows("2026-09-01","2026-09-16","123","task");
    when(repository.latestSyncTime("jd_low_activity")).thenReturn("2026-09-17T12:00:00.002000");
    assertNotSame(first,service.analyze("2026-09-01","2026-09-16","123","task"));
    service.analyze("2026-09-01","2026-09-16","456","task");
    verify(repository,times(2)).readJdLowActivityRows("2026-09-01","2026-09-16","123","task");
    verify(repository).readJdLowActivityRows("2026-09-01","2026-09-16","456","task");
  }
  @Test void adpfluxInvalidatesForBalanceOnlyUpdatesAndKeepsFiltersSeparate(){
    var repository=mock(AdpfluxRepository.class);var service=new AdpfluxService(repository,null,null,null);
    when(repository.latestSyncTime()).thenReturn("report-v1");when(repository.latestBalanceSyncTime()).thenReturn("balance-v1");
    when(repository.readRows(anyString(),anyString(),anyString(),anyString(),anyBoolean())).thenReturn(List.of());
    when(repository.readCurrentBalances()).thenReturn(Map.of());
    var first=service.analyze("2026-09-01","2026-09-17","123","enabled",true);
    assertSame(first,service.analyze("2026-09-01","2026-09-17","123","enabled",true));verify(repository,times(1)).readCurrentBalances();
    when(repository.latestBalanceSyncTime()).thenReturn("balance-v2");
    var second=service.analyze("2026-09-01","2026-09-17","123","enabled",true);
    assertNotSame(first,second);assertEquals("balance-v2",second.get("balanceCachedAt"));
    when(repository.latestSyncTime()).thenReturn("report-v2");
    assertNotSame(second,service.analyze("2026-09-01","2026-09-17","123","enabled",true));
    service.analyze("2026-09-01","2026-09-17","123","disabled",false);
    verify(repository).readRows("2026-09-01","2026-09-17","123","disabled",false);
  }
}
