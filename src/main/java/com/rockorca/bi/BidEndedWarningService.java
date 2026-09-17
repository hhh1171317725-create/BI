package com.rockorca.bi;

import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/** Single-flight upstream verification shared by viewers. No daily archives are overwritten by range totals. */
@Service
public class BidEndedWarningService {
  private final BidHistoryStore history;
  private final BidServerSyncStore store;
  private final BidCredentialCipher cipher;
  private final BidServerSyncService sync;
  private final ExecutorService worker=Executors.newFixedThreadPool(2);
  private final Map<Long,Job> jobs=new HashMap<>();
  private static final class Job {
    final LocalDate day;final String revision;final long started=System.currentTimeMillis();
    volatile Map<String,Object> result=Map.of("state","running","rows",List.of());
    Job(LocalDate day,String revision){this.day=day;this.revision=revision;}
  }
  public BidEndedWarningService(BidHistoryStore history,BidServerSyncStore store,BidCredentialCipher cipher,BidServerSyncService sync){
    this.history=history;this.store=store;this.cipher=cipher;this.sync=sync;
  }
  synchronized Map<String,Object> read(long owner,boolean refresh)throws Exception{
    if(!sync.allowed(owner))throw new IllegalStateException("共享来源权限已失效");
    var state=store.get(owner);var today=LocalDate.now(ReportService.BEIJING);
    String revision=Objects.toString(state.get("credentialRevision"),"");var job=jobs.get(owner);
    if(job!=null&&job.day.equals(today)&&job.revision.equals(revision)){
      if("running".equals(job.result.get("state"))||(!refresh&&System.currentTimeMillis()-job.started<300_000))return job.result;
    }
    var next=new Job(today,revision);jobs.put(owner,next);
    worker.submit(()->verify(owner,state,next));return next.result;
  }
  void verify(long owner,Map<String,Object> state,Job job){
    try{
      var candidates=history.readEndedPlans(owner,job.day);
      List<Map<String,Object>> latest=List.of();
      if(!candidates.isEmpty()){
        if(!state.containsKey("credential"))throw new IllegalStateException("credential");
        String cookie=cipher.decrypt(owner,Objects.toString(state.get("credential"),""));
        latest=sync.collectPlanTotals(state,cookie,candidates,job.day);
      }
      if(!sync.allowed(owner)||!job.revision.equals(Objects.toString(store.get(owner).get("credentialRevision"),"")))throw new IllegalStateException("credential");
      var warnings=new ArrayList<Map<String,Object>>();int valid=0;
      for(var row:latest){
        var created=BidEndedWarning.date(row.get("promotion_create_time"));double bid=BidEndedWarning.number(row.get("cpa_bid"));
        if(created==null||!Double.isFinite(bid)||bid<=0)continue;
        double cost=BidEndedWarning.number(row.get("stat_cost")),conversions=BidEndedWarning.number(row.get("convert_cnt"));
        if(!Double.isFinite(cost)||cost<0||!Double.isFinite(conversions)||conversions<0)continue;
        valid++;
        var warning=BidEndedWarning.evaluate(row,job.day,cost,conversions,created.toString(),job.day.toString(),0);
        if(warning!=null){warning.remove("archive_complete");warning.put("verified",true);warnings.add(warning);}
      }
      warnings.sort(Comparator.comparingDouble((Map<String,Object> r)->((Number)r.get("overall_cost")).doubleValue()).reversed());
      job.result=Map.of("state","ready","rows",warnings,"count",warnings.size(),"asOf",job.day.toString(),
          "checkedAt",Instant.now().toString(),"checkedCount",valid,"unverifiedCount",Math.max(0,candidates.size()-valid));
    }catch(Exception error){
      job.result=Map.of("state","error","rows",List.of(),"error","接口累计数据核验失败，请确认共享同步凭据有效后重试；未使用旧归档判断预警");
    }
  }
  @PreDestroy public void close(){worker.shutdownNow();}
}
