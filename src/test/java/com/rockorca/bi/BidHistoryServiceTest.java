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
    var cipher=new BidCredentialCipher(config);
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
    assertEquals("2026-09-10",store.get(7).get("historyLastDate"));assertEquals("ready",store.get(7).get("historyState"));
    service.capture(7,LocalDate.of(2026,9,11));verify(sync,times(1)).collectHistory(anyMap(),anyString(),any());
  }

  @Test void nextCaptureIsNextBeijing0030(){
    long expected=LocalDate.of(2026,9,12).atTime(0,30).atZone(ReportService.BEIJING).toInstant().toEpochMilli();
    assertEquals(expected,BidHistoryService.nextCaptureAt(LocalDate.of(2026,9,11)));
  }
}
