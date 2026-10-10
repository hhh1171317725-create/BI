package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class BidHistoryServiceTest {
  @TempDir Path dir;
  private final MemoryStore store=new MemoryStore();
  private final BidServerSyncService sync=mock(BidServerSyncService.class);
  private final BidProviderRawStore raw=mock(BidProviderRawStore.class);
  private final BidHistoryStore history=mock(BidHistoryStore.class);
  private BidCredentialCipher cipher;
  private BidHistoryService service;

  static class MemoryStore extends BidServerSyncStore {
    final Map<Long,Map<String,Object>> states=new HashMap<>();
    final Connection connection=mock(Connection.class);
    MemoryStore(){super(null,null);}
    @Override synchronized Map<String,Object> get(long owner){return new LinkedHashMap<>(states.getOrDefault(owner,Map.of()));}
    @Override synchronized Map<String,Object> update(long owner,Update action)throws Exception{
      var state=get(owner);action.apply(connection,state);states.put(owner,state);return get(owner);
    }
  }

  @BeforeEach void setup()throws Exception{
    var config=mock(RuntimeConfig.class);when(config.runtimeDir()).thenReturn(dir);
    cipher=new BidCredentialCipher(config);
    var state=new LinkedHashMap<String,Object>();state.put("enabled",true);state.put("credential",cipher.encrypt(7,"cookie"));
    state.put("credentialRevision","revision");state.put("clientUser","123");state.put("mainUserId","456");store.states.put(7L,state);
    when(sync.allowed(7)).thenReturn(true);
    var row=new LinkedHashMap<String,Object>();row.put("promotion_id","123");row.put("source_platform","byte");
    row.put("media_account_id","456");row.put("advertiser_id","789");row.put("stat_cost",1);row.put("convert_cnt",2);
    row.put("active_register",3);row.put("cpa_bid",4);row.put("provider_data",Map.of("full_field","saved"));
    when(sync.collectHistory(anyMap(),eq("cookie"),eq(LocalDate.of(2026,9,11))))
        .thenReturn(Map.of("date","2026-09-10","rows",List.of(row)));
    service=new BidHistoryService(store,cipher,sync,raw,history);
  }

  @AfterEach void close(){service.close();}

  @Test void savesProviderAndNormalizedRowsOnceForYesterday()throws Exception{
    service.capture(7,LocalDate.of(2026,9,11));
    verify(raw).replace(eq(store.connection),eq(7L),eq("2026-09-10"),anyList());
    verify(history).replace(eq(store.connection),eq(7L),eq(LocalDate.of(2026,9,10)),anyList());
    verify(history).archiveCommitted();
    assertEquals("2026-09-10",store.get(7).get("historyLastDate"));assertEquals("ready",store.get(7).get("historyState"));
    service.capture(7,LocalDate.of(2026,9,11));verify(sync,times(1)).collectHistory(anyMap(),anyString(),any());
  }

  @Test void nextCaptureIsNextBeijing0030(){
    long expected=LocalDate.of(2026,9,12).atTime(0,30).atZone(ReportService.BEIJING).toInstant().toEpochMilli();
    assertEquals(expected,BidHistoryService.nextCaptureAt(LocalDate.of(2026,9,11)));
  }
  @Test void archiveFailurePersistsAcrossRetriesAndRestartUntilArchiveCommitSucceeds()throws Exception{
    var runDate=LocalDate.of(2026,9,11);
    store.update(7,(connection,state)->{
      state.put("historyLastDate","2026-09-09");state.put("historyLastSuccess","old-archive");
      state.put("error","current snapshot failure");state.put("failureAt","current-incident");state.put("lastSuccess","current-snapshot");
    });
    doThrow(new java.io.IOException("private cookie body")).when(sync).collectHistory(anyMap(),anyString(),eq(runDate));
    service.capture(7,runDate);var failed=store.get(7);String failureAt=failed.get("historyFailureAt").toString();
    assertDoesNotThrow(()->java.time.Instant.parse(failureAt));assertEquals("retrying",failed.get("historyState"));
    assertEquals("2026-09-09",failed.get("historyLastDate"));assertEquals("old-archive",failed.get("historyLastSuccess"));
    assertFalse(failed.get("historyError").toString().contains("private cookie body"));verify(history,never()).replace(any(),anyLong(),any(),anyList());
    service.close();service=new BidHistoryService(store,cipher,sync,raw,history);
    assertEquals(failureAt,store.get(7).get("historyFailureAt"));store.update(7,(connection,state)->state.put("historyRetryAt",0L));
    doAnswer(call->{
      var current=store.get(7);assertEquals("running",current.get("historyState"));
      assertEquals(failed.get("historyError"),current.get("historyError"));assertEquals(failureAt,current.get("historyFailureAt"));
      throw new java.io.IOException("still failing");
    }).when(sync).collectHistory(anyMap(),anyString(),eq(runDate));
    service.capture(7,runDate);assertEquals(failureAt,store.get(7).get("historyFailureAt"));
    store.update(7,(connection,state)->state.put("historyRetryAt",0L));
    doReturn(Map.of("date","2026-09-10","rows",List.of(Map.of("promotion_id","123"))))
        .when(sync).collectHistory(anyMap(),anyString(),eq(runDate));
    service.capture(7,runDate);var recovered=store.get(7);
    assertEquals("ready",recovered.get("historyState"));assertEquals("",recovered.get("historyError"));assertFalse(recovered.containsKey("historyFailureAt"));
    assertEquals("2026-09-10",recovered.get("historyLastDate"));assertNotEquals("old-archive",recovered.get("historyLastSuccess"));
    assertEquals("current snapshot failure",recovered.get("error"));assertEquals("current-incident",recovered.get("failureAt"));
    assertEquals("current-snapshot",recovered.get("lastSuccess"));verify(history).archiveCommitted();
  }
  @Test void failedArchiveWriteKeepsIncidentAndPreviousSuccessMetadata()throws Exception{
    store.update(7,(connection,state)->{
      state.put("historyError","previous failure");state.put("historyFailureAt","2026-09-10T01:00:00Z");
      state.put("historyLastDate","2026-09-09");state.put("historyLastSuccess","old-archive");
    });
    doAnswer(call->{
      assertEquals("previous failure",store.get(7).get("historyError"));
      assertEquals("2026-09-10T01:00:00Z",store.get(7).get("historyFailureAt"));throw new java.sql.SQLException("private database body");
    }).when(history).replace(any(),eq(7L),any(),anyList());
    service.capture(7,LocalDate.of(2026,9,11));var failed=store.get(7);
    assertEquals("retrying",failed.get("historyState"));assertEquals("2026-09-10T01:00:00Z",failed.get("historyFailureAt"));
    assertEquals("2026-09-09",failed.get("historyLastDate"));assertEquals("old-archive",failed.get("historyLastSuccess"));
    assertFalse(failed.get("historyError").toString().contains("private database body"));verify(history,never()).archiveCommitted();
  }
}
