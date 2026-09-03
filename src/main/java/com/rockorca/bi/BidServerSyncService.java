package com.rockorca.bi;

import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BidServerSyncService {
  private static final Logger LOG=LoggerFactory.getLogger(BidServerSyncService.class);
  private final BidServerSyncStore store;
  private final BidCredentialCipher cipher;
  private final BidMonitorApiController upstream;
  private final BidSnapshotController snapshots;
  private final UserService users;
  private final ExecutorService workers=Executors.newFixedThreadPool(2);
  private final Semaphore slots=new Semaphore(2);

  public BidServerSyncService(BidServerSyncStore store,BidCredentialCipher cipher,
      BidMonitorApiController upstream,BidSnapshotController snapshots,UserService users) {
    this.store=store;this.cipher=cipher;this.upstream=upstream;this.snapshots=snapshots;this.users=users;
  }

  Map<String,Object> status(long owner) throws Exception { return view(owner,store.get(owner)); }

  static Map<String,Object> view(long owner,Map<String,Object> state) {
    var output=new LinkedHashMap<String,Object>();
    output.put("userId",Long.toString(owner));
    output.put("configured",state.containsKey("credential"));
    output.put("enabled",Boolean.TRUE.equals(state.get("enabled")));
    for (String key:List.of("clientUser","mainUserId","minutes","createdDays","state","error","lastSuccess","dueAt"))
      if (state.containsKey(key)) output.put(key,state.get(key));
    return output;
  }

  Map<String,Object> start(long owner,Map<String,Object> input) throws Exception {
    int minutes=option(input,"minutes",Set.of(5,10,15,30,60));
    int days=option(input,"createdDays",Set.of(7,14,30,90));
    String user=text(input,"clientUser"),main=text(input,"mainUserId");
    if (!user.matches("[0-9]{1,30}")||!main.matches("[0-9]{1,30}")) throw new IllegalArgumentException("请填写有效的 client-user 和 main-user-id");
    String cookie=BidMonitorApiController.normalizeCookie(text(input,"cookie"));
    if(cookie.length()>16000)throw new IllegalArgumentException("Cookie 过长");
    var saved=store.update(owner,(connection,state)->{
      String value=cookie;
      if(value.isBlank()) {
        if(!state.containsKey("credential"))throw new IllegalArgumentException("首次启用请填写 Cookie");
        if(!user.equals(state.get("clientUser"))||!main.equals(state.get("mainUserId")))
          throw new IllegalArgumentException("更换创量账户时请同时更新 Cookie");
        try { value=cipher.decrypt(owner,text(state,"credential")); }
        catch(Exception error){throw new IllegalArgumentException("保存的凭据无法解密，请重新填写 Cookie");}
      }
      BidMonitorApiController.validateCookieUser(value,user);
      state.put("credential",cipher.encrypt(owner,value));
      state.put("clientUser",user);state.put("mainUserId",main);
      state.put("minutes",minutes);state.put("createdDays",days);
      queue(state);
    });
    return view(owner,saved);
  }

  Map<String,Object> command(long owner,String action) throws Exception {
    var saved=store.update(owner,(connection,state)->{
      if(action.equals("run")) {
        if(!Boolean.TRUE.equals(state.get("enabled")))throw new IllegalArgumentException("请先保存并启用同步");
        if(!"running".equals(state.get("state")))queue(state);
      } else {
        state.put("token",UUID.randomUUID().toString());state.put("enabled",false);
        state.put("dueAt",0L);state.put("state","stopped");state.put("error","");
        if(action.equals("forget"))state.remove("credential");
      }
    });
    return view(owner,saved);
  }

  private static void queue(Map<String,Object> state) {
    state.put("token",UUID.randomUUID().toString());state.put("enabled",true);
    state.put("dueAt",System.currentTimeMillis());state.put("state","waiting");state.put("error","");
  }

  boolean allowed(long owner) {
    var actor=users.findById(owner);
    return actor!=null&&actor.active()&&users.canUseTool(actor,"bidMonitor");
  }

  @Scheduled(fixedDelay=5000,initialDelay=15000)
  public void dispatch() {
    try {
      for(long owner:store.due(System.currentTimeMillis())) {
        if(!slots.tryAcquire())break;
        try { workers.submit(()->{try{run(owner);}finally{slots.release();}}); }
        catch(RejectedExecutionException error){slots.release();}
      }
    } catch(Exception error){LOG.warn("Bid server sync could not read its schedule; retrying later");}
  }

  void run(long owner) {
    String token=UUID.randomUUID().toString();
    try {
      var state=store.update(owner,(connection,current)->{
        long due=((Number)current.getOrDefault("dueAt",0L)).longValue();
        if(!Boolean.TRUE.equals(current.get("enabled"))||due<=0||due>System.currentTimeMillis())return;
        current.put("token",token);current.put("state","running");
        // A crashed process leaves a short lease; another instance can resume after expiry.
        current.put("dueAt",System.currentTimeMillis()+180000L);
      });
      if(!token.equals(state.get("token")))return;
      if(!allowed(owner))throw new IllegalStateException("permission");
      String cookie=cipher.decrypt(owner,text(state,"credential"));
      var snapshot=collect(state,cookie);
      snapshots.initialize();
      store.update(owner,(connection,current)->{
        if(!current(current,token))return;
        if(!allowed(owner))throw new IllegalStateException("permission");
        // Revalidate at commit time to reject a request which crossed Beijing midnight.
        var validated=BidSnapshotController.validate(snapshot);
        snapshots.write(connection,owner,validated);
        current.put("lastSuccess",validated.get("updatedAt"));current.put("state","ready");
        current.put("error","");current.put("dueAt",System.currentTimeMillis()+((Number)current.get("minutes")).longValue()*60000L);
      });
    } catch(Exception error) {
      try {
        store.update(owner,(connection,current)->{
          if(!current(current,token))return;
          current.put("enabled",false);current.put("dueAt",0L);current.put("state","paused");
          current.put("error",failure(error));
        });
      } catch(Exception ignored){LOG.warn("Bid server sync could not save failure status for user {}",owner);}
    }
  }

  static boolean current(Map<String,Object> state,String token) {
    return Boolean.TRUE.equals(state.get("enabled"))&&token.equals(state.get("token"));
  }

  Map<String,Object> collect(Map<String,Object> state,String cookie) throws Exception {
    LocalDate today=LocalDate.now(ReportService.BEIJING);
    String start=today.minusDays(((Number)state.get("createdDays")).intValue()-1L).toString();
    var input=new LinkedHashMap<String,Object>(Map.of("cookie",cookie,"clientUser",state.get("clientUser"),
        "mainUserId",state.get("mainUserId"),"startDate",today.toString(),"endDate",today.toString(),
        "createdStart",start,"createdEnd",today.toString()));
    var rows=new ArrayList<Map<String,Object>>();long total=-1;
    for(int page=1;page<=2;page++) {
      input.put("page",page);
      var result=upstream.page(input);
      long count=Long.parseLong(String.valueOf(result.get("total")));
      if(count<0||(total>=0&&total!=count))throw new IllegalArgumentException("incomplete");
      total=count;
      if(!(result.get("rows") instanceof List<?> batch)||batch.size()!=Math.min(100,Math.max(0,total-(page-1)*100L)))
        throw new IllegalArgumentException("incomplete");
      for(Object item:batch) {
        if(!(item instanceof Map<?,?> raw))throw new IllegalArgumentException("incomplete");
        var row=new LinkedHashMap<String,Object>();
        for(String key:List.of("promotion_id","promotion_name","media_account_id","stat_cost","convert_cnt","active_register","cpa_bid"))row.put(key,raw.get(key));
        for(String key:List.of("promotion_id","media_account_id")){
          Object id=row.get(key);
          if(id instanceof Float||id instanceof Double)throw new IllegalArgumentException("incomplete");
          if(id!=null)row.put(key,id.toString());
        }
        Object name=raw.get("media_account_name");
        row.put("media_account_name",name==null||name.toString().isBlank()?raw.get("advertiser_nick"):name);
        rows.add(row);
      }
      if(rows.size()>=Math.min(200,total))break;
    }
    return BidSnapshotController.validate(Map.of("date",today.toString(),"rows",rows,"selection","spend_desc_top_200",
        "upstreamTotal",total,"createdStart",start,"createdEnd",today.toString()));
  }

  static String failure(Exception error) {
    if("permission".equals(error.getMessage()))return "网站账户已停用或工具权限已撤销，同步已暂停";
    // Do not persist upstream bodies, cookies, or exception stacks in user-visible status.
    String message=Objects.toString(error.getMessage(),"");
    var code=java.util.regex.Pattern.compile("code=([0-9-]{1,10})").matcher(message);
    return "服务器同步失败"+(code.find()?"（创量 code="+code.group(1)+"）":"")
        +"，已暂停并保留旧快照。请核对有效登录凭据、接口权限及网络后重新启用；不会绕过验证。";
  }

  private static String text(Map<String,Object> data,String key){return Objects.toString(data.get(key),"").trim();}
  private static int option(Map<String,Object> data,String key,Set<Integer> allowed){
    int value=Integer.parseInt(text(data,key));if(!allowed.contains(value))throw new IllegalArgumentException("同步间隔或创建范围无效");return value;
  }
  @PreDestroy public void close(){workers.shutdownNow();}
}
