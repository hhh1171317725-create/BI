package com.rockorca.bi;

import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BidHistoryService {
  private static final Logger LOG=LoggerFactory.getLogger(BidHistoryService.class);
  private static final long RETRY_MILLIS=600_000L;
  private final BidServerSyncStore store;
  private final BidCredentialCipher cipher;
  private final BidServerSyncService sync;
  private final BidProviderRawStore rawStore;
  private final BidHistoryStore history;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();

  public BidHistoryService(BidServerSyncStore store,BidCredentialCipher cipher,BidServerSyncService sync,
      BidProviderRawStore rawStore,BidHistoryStore history){
    this.store=store;this.cipher=cipher;this.sync=sync;this.rawStore=rawStore;this.history=history;
  }

  @Scheduled(fixedDelay=60_000,initialDelay=30_000)
  public void dispatch(){
    ZonedDateTime now=ZonedDateTime.now(ReportService.BEIJING);
    if(now.toLocalTime().isBefore(LocalTime.of(0,30)))return;
    try{
      String reportDate=now.toLocalDate().minusDays(1).toString();
      for(long owner:store.historyDue(reportDate,System.currentTimeMillis()))worker.submit(()->capture(owner,now.toLocalDate()));
    }catch(Exception error){LOG.warn("Bid history scheduler could not read enabled owners; retrying later");}
  }

  void capture(long owner,LocalDate runDate){
    String token=UUID.randomUUID().toString();
    LocalDate reportDate=runDate.minusDays(1);
    try{
      var state=store.update(owner,(connection,current)->{
        if(!Boolean.TRUE.equals(current.get("enabled"))||!current.containsKey("credential"))return;
        if(reportDate.toString().equals(current.get("historyLastDate")))return;
        long retryAt=((Number)current.getOrDefault("historyRetryAt",0L)).longValue();
        if(retryAt>System.currentTimeMillis())return;
        current.put("historyToken",token);current.put("historyState","running");current.put("historyError","");
        current.put("historyRetryAt",System.currentTimeMillis()+180_000L);
      });
      if(!token.equals(state.get("historyToken")))return;
      if(!sync.allowed(owner))throw new IllegalStateException("permission");
      String cookie;
      try{cookie=cipher.decrypt(owner,Objects.toString(state.get("credential"),""));}
      catch(Exception error){throw new IllegalStateException("credential");}
      String credentialRevision=Objects.toString(state.get("credentialRevision"),"");
      var snapshot=sync.collectHistory(state,cookie,runDate);
      rawStore.initialize();history.initialize();
      store.update(owner,(connection,current)->{
        if(!token.equals(current.get("historyToken"))||!Boolean.TRUE.equals(current.get("enabled"))
            ||!credentialRevision.equals(Objects.toString(current.get("credentialRevision"),""))||!sync.allowed(owner))return;
        var rows=BidServerSyncService.rawRows(snapshot);
        rawStore.replace(connection,owner,reportDate.toString(),rows);
        history.replace(connection,owner,reportDate,rows);
        current.put("historyLastDate",reportDate.toString());current.put("historyLastSuccess",Instant.now().toString());
        current.put("historyState","ready");current.put("historyError","");current.remove("historyToken");
        current.put("historyRetryAt",nextCaptureAt(runDate));
      });
    }catch(Exception error){
      try{
        store.update(owner,(connection,current)->{
          if(!token.equals(current.get("historyToken")))return;
          current.put("historyState","retrying");
          current.put("historyError",BidServerSyncService.requiresAttention(error)
              ?"昨日历史归档失败，请更新同步凭据":"昨日历史归档失败，10 分钟后自动重试");
          current.put("historyRetryAt",System.currentTimeMillis()+RETRY_MILLIS);current.remove("historyToken");
        });
      }catch(Exception ignored){LOG.warn("Bid history scheduler could not save failure status for user {}",owner);}
    }
  }

  static long nextCaptureAt(LocalDate runDate){
    return runDate.plusDays(1).atTime(0,30).atZone(ReportService.BEIJING).toInstant().toEpochMilli();
  }

  @PreDestroy public void close(){worker.shutdownNow();}
}
