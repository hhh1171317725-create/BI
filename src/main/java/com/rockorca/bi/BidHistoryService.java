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
  private final Set<Long> pendingOwners=ConcurrentHashMap.newKeySet();

  public BidHistoryService(BidServerSyncStore store,BidCredentialCipher cipher,BidServerSyncService sync,
      BidProviderRawStore rawStore,BidHistoryStore history){
    this.store=store;this.cipher=cipher;this.sync=sync;this.rawStore=rawStore;this.history=history;
  }

  @Scheduled(fixedDelay=60_000,initialDelay=30_000)
  public void dispatch(){
    dispatch(ZonedDateTime.now(ReportService.BEIJING));
  }

  void dispatch(ZonedDateTime now){
    if(now.toLocalTime().isBefore(LocalTime.of(0,30)))return;
    try{
      String reportDate=now.toLocalDate().minusDays(1).toString();
      for(long owner:store.historyDue(reportDate,System.currentTimeMillis())){
        if(!pendingOwners.add(owner))continue;
        try{
          worker.submit(()->{try{capture(owner,now.toLocalDate());}finally{pendingOwners.remove(owner);}});
        }catch(RejectedExecutionException error){pendingOwners.remove(owner);}
      }
    }catch(Exception error){LOG.warn("Bid history scheduler could not read enabled owners; retrying later");}
  }

  void capture(long owner,LocalDate runDate){
    String token=UUID.randomUUID().toString();
    LocalDate reportDate=runDate.minusDays(1);
    Map<String,Object> claimed=null;
    try{
      var state=store.update(owner,(connection,current)->{
        if(!Boolean.TRUE.equals(current.get("enabled"))||!current.containsKey("credential"))return;
        if(reportDate.toString().equals(current.get("historyLastDate")))return;
        long retryAt=((Number)current.getOrDefault("historyRetryAt",0L)).longValue();
        if(retryAt>System.currentTimeMillis())return;
        current.put("historyToken",token);current.put("historyState","running");current.putIfAbsent("historyError","");
        current.put("historyRetryAt",System.currentTimeMillis()+180_000L);
      });
      if(!token.equals(state.get("historyToken")))return;
      claimed=state;
      if(!sync.allowed(owner))throw new IllegalStateException("permission");
      String cookie;
      try{cookie=cipher.decrypt(owner,Objects.toString(state.get("credential"),""));}
      catch(Exception error){throw new IllegalStateException("credential");}
      String credentialRevision=Objects.toString(state.get("credentialRevision"),"");
      var snapshot=sync.collectHistory(state,cookie,runDate);
      rawStore.initialize();history.initialize();
      boolean[] committed={false};
      store.update(owner,(connection,current)->{
        if(!current(current,token,credentialRevision)||!sync.allowed(owner))return;
        var rows=BidServerSyncService.rawRows(snapshot);
        rawStore.replace(connection,owner,reportDate.toString(),rows);
        history.replace(connection,owner,reportDate,rows);
        current.put("historyLastDate",reportDate.toString());current.put("historyLastSuccess",Instant.now().toString());
        current.put("historyState","ready");current.put("historyError","");current.remove("historyFailureAt");current.remove("historyToken");
        current.put("historyRetryAt",nextCaptureAt(runDate));
        committed[0]=true;
      });
      if(committed[0])history.archiveCommitted();
    }catch(Exception error){
      if(claimed==null)return;
      String credentialRevision=Objects.toString(claimed.get("credentialRevision"),"");
      try{
        store.update(owner,(connection,current)->{
          if(!current(current,token,credentialRevision))return;
          current.put("historyState","retrying");
          current.put("historyError",BidServerSyncService.requiresAttention(error)
              ?"昨日历史归档失败，请更新同步凭据":"昨日历史归档失败，10 分钟后自动重试");
          current.putIfAbsent("historyFailureAt",Instant.now().toString());
          current.put("historyRetryAt",System.currentTimeMillis()+RETRY_MILLIS);current.remove("historyToken");
        });
      }catch(Exception ignored){LOG.warn("Bid history scheduler could not save failure status for user {}",owner);}
    }
  }

  private static boolean current(Map<String,Object> state,String token,String credentialRevision){
    return Boolean.TRUE.equals(state.get("enabled"))&&token.equals(state.get("historyToken"))
        &&credentialRevision.equals(Objects.toString(state.get("credentialRevision"),""));
  }

  static long nextCaptureAt(LocalDate runDate){
    return runDate.plusDays(1).atTime(0,30).atZone(ReportService.BEIJING).toInstant().toEpochMilli();
  }

  @PreDestroy public void close(){worker.shutdownNow();pendingOwners.clear();}
}
