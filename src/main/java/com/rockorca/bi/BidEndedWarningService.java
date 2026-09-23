package com.rockorca.bi;

import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Single-flight upstream verification shared by viewers. No daily archives are overwritten by range totals. */
@Service
public class BidEndedWarningService {
  private final BidHistoryStore history;
  private final BidServerSyncStore store;
  private final BidCredentialCipher cipher;
  private final BidServerSyncService sync;
  private final LongSupplier clock;
  private final ExecutorService worker=Executors.newFixedThreadPool(2);
  private final Map<Long,Job> jobs=new HashMap<>();
  private static final class Job {
    final LocalDate day;final String revision;final long started;volatile long completed;
    volatile Map<String,Object> result=Map.of("state","running","rows",List.of());
    final List<Map<String,Object>> partialWarnings=new ArrayList<>();int checked;String message="";
    Job(LocalDate day,String revision,long started){this.day=day;this.revision=revision;this.started=started;}
    void progress(String message){this.message=message;publish();}
    void accept(List<Map<String,Object>> rows){
      for(var row:rows){
        if(!valid(row))continue;checked++;
        var warning=warning(row,day);if(warning!=null)partialWarnings.add(warning);
      }
      partialWarnings.sort(Comparator.comparingDouble((Map<String,Object> r)->((Number)r.get("overall_cost")).doubleValue()).reversed());
      publish();
    }
    void publish(){result=Map.of("state","running","rows",List.copyOf(partialWarnings),"progress",message,
        "startedAt",Instant.ofEpochMilli(started).toString(),"checkedCount",checked,"asOf",day.toString());}
  }
  @Autowired
  public BidEndedWarningService(BidHistoryStore history,BidServerSyncStore store,BidCredentialCipher cipher,BidServerSyncService sync){
    this(history,store,cipher,sync,System::currentTimeMillis);
  }
  BidEndedWarningService(BidHistoryStore history,BidServerSyncStore store,BidCredentialCipher cipher,BidServerSyncService sync,LongSupplier clock){
    this.history=history;this.store=store;this.cipher=cipher;this.sync=sync;this.clock=clock;
  }
  synchronized Map<String,Object> read(long owner,boolean refresh)throws Exception{
    if(!sync.allowed(owner))throw new IllegalStateException("共享来源权限已失效");
    var state=store.get(owner);var today=LocalDate.now(ReportService.BEIJING);
    String revision=Objects.toString(state.get("credentialRevision"),"");var job=jobs.get(owner);
    if(job!=null&&job.day.equals(today)&&job.revision.equals(revision)){
      if("running".equals(job.result.get("state"))||(!refresh&&clock.getAsLong()-job.completed<300_000))return job.result;
    }
    var next=new Job(today,revision,clock.getAsLong());next.progress("正在读取已结束赔付期的归档计划…");jobs.put(owner,next);
    worker.submit(()->verify(owner,state,next));return next.result;
  }
  void verify(long owner,Map<String,Object> state,Job job){
    try{
      var candidates=history.readEndedPlans(owner,job.day);
      List<Map<String,Object>> latest=List.of();
      if(!candidates.isEmpty()){
        if(!state.containsKey("credential"))throw new IllegalStateException("credential");
        String cookie=cipher.decrypt(owner,Objects.toString(state.get("credential"),""));
        latest=sync.collectPlanTotals(state,cookie,candidates,job.day,job::progress,job::accept);
      }
      if(!sync.allowed(owner)||!job.revision.equals(Objects.toString(store.get(owner).get("credentialRevision"),"")))throw new IllegalStateException("credential");
      var warnings=new ArrayList<Map<String,Object>>();int valid=0;
      for(var row:latest){
        if(!valid(row))continue;
        valid++;
        var warning=warning(row,job.day);if(warning!=null)warnings.add(warning);
      }
      warnings.sort(Comparator.comparingDouble((Map<String,Object> r)->((Number)r.get("overall_cost")).doubleValue()).reversed());
      job.completed=clock.getAsLong();
      job.result=Map.of("state","ready","rows",warnings,"count",warnings.size(),"asOf",job.day.toString(),
          "checkedAt",Instant.now().toString(),"checkedCount",valid,"unverifiedCount",Math.max(0,candidates.size()-valid));
    }catch(Exception error){
      job.completed=clock.getAsLong();
      job.result=Map.of("state","error","rows",List.of(),"error","接口累计数据核验失败，请确认共享同步凭据有效后重试；未使用旧归档判断预警");
    }
  }
  private static boolean valid(Map<String,Object> row){
    double bid=BidEndedWarning.number(row.get("cpa_bid")),cost=BidEndedWarning.number(row.get("stat_cost")),conversions=BidEndedWarning.number(row.get("convert_cnt"));
    return BidEndedWarning.date(row.get("promotion_create_time"))!=null&&Double.isFinite(bid)&&bid>0
        &&Double.isFinite(cost)&&cost>=0&&Double.isFinite(conversions)&&conversions>=0;
  }
  private static Map<String,Object> warning(Map<String,Object> row,LocalDate day){
    var warning=BidEndedWarning.evaluate(row,day,BidEndedWarning.number(row.get("stat_cost")),BidEndedWarning.number(row.get("convert_cnt")),
        BidEndedWarning.date(row.get("promotion_create_time")).toString(),day.toString(),0);
    if(warning!=null){warning.remove("archive_complete");warning.put("verified",true);}return warning;
  }
  @PreDestroy public void close(){worker.shutdownNow();}
}
