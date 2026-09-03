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

  Map<String,Object> pricing(long owner)throws Exception{return pricingView(owner,store.get(owner));}

  private static Map<String,Object> pricingView(long owner,Map<String,Object> state){
    return Map.of("userId",Long.toString(owner),"rules",state.getOrDefault("taskRules",List.of()),
        "revision",state.getOrDefault("pricingRevision",""));
  }

  Map<String,Object> savePricing(long owner,Map<String,Object> input)throws Exception{
    var rules=validateRules(input.get("rules"));
    return pricingView(owner,store.update(owner,(connection,state)->{
      if(!text(input,"revision").equals(text(state,"pricingRevision")))
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"任务价格已在其他页面修改，请重新读取");
      state.put("taskRules",rules);state.put("pricingRevision",UUID.randomUUID().toString());
    }));
  }

  static List<Map<String,Object>> validateRules(Object input){
    if(!(input instanceof List<?> list)||list.size()>50)throw new IllegalArgumentException("最多配置 50 个任务");
    var result=new ArrayList<Map<String,Object>>();var names=new HashSet<String>();var keywords=new HashSet<String>();
    for(Object item:list){
      if(!(item instanceof Map<?,?> raw))throw new IllegalArgumentException("任务格式无效");
      String name=Objects.toString(raw.get("name"),"").trim(),keyword=Objects.toString(raw.get("keyword"),"").trim();
      if(name.isBlank()||name.length()>80||keyword.isBlank()||keyword.length()>80||
          !names.add(name.toLowerCase(Locale.ROOT))||!keywords.add(keyword.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("任务名及账户关键词须为 1 至 80 字，且不能重复");
      java.math.BigDecimal price;
      try{price=new java.math.BigDecimal(Objects.toString(raw.get("price"),""));}
      catch(NumberFormatException error){throw new IllegalArgumentException("请填写有效结算单价");}
      if(price.signum()<=0||price.compareTo(new java.math.BigDecimal("1000000"))>0||price.scale()>6)
        throw new IllegalArgumentException("结算单价须大于 0、不超过 1000000，最多 6 位小数");
      result.add(Map.of("name",name,"keyword",keyword,"price",price.toPlainString()));
    }
    return result;
  }

  static Map<String,Object> view(long owner,Map<String,Object> state) {
    var output=new LinkedHashMap<String,Object>();
    output.put("userId",Long.toString(owner));
    output.put("configured",state.containsKey("credential"));
    output.put("enabled",Boolean.TRUE.equals(state.get("enabled")));
    for (String key:List.of("clientUser","mainUserId","minutes","state","error","lastSuccess","dueAt","progress"))
      if (state.containsKey(key)) output.put(key,state.get(key));
    output.put("createdDays",4);
    return output;
  }

  Map<String,Object> start(long owner,Map<String,Object> input) throws Exception {
    int minutes=option(input,"minutes",Set.of(5,10,15,30,60));
    int days=4;
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
    state.put("dueAt",System.currentTimeMillis());state.put("state","waiting");state.put("error","");state.put("progress","");
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
      var snapshot=collect(state,cookie,(done,total)->store.update(owner,(connection,current)->{
        if(!current(current,token))throw new CancellationException();
        if(!allowed(owner))throw new IllegalStateException("permission");
        current.put("dueAt",System.currentTimeMillis()+180000L);
        current.put("progress","已读取 "+done+(total<0?"":" / "+total)+" 条");
      }));
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
    return collect(state,cookie,(done,total)->{});
  }

  @FunctionalInterface interface Progress { void update(int done,long total)throws Exception; }
  static LocalDate creationStart(LocalDate today){return today.minusDays(3);}

  Map<String,Object> collect(Map<String,Object> state,String cookie,Progress progress) throws Exception {
    LocalDate today=LocalDate.now(ReportService.BEIJING);
    String start=creationStart(today).toString();
    var input=new LinkedHashMap<String,Object>(Map.of("cookie",cookie,"clientUser",state.get("clientUser"),
        "mainUserId",state.get("mainUserId"),"startDate",today.toString(),"endDate",today.toString(),
        "createdStart",start,"createdEnd",today.toString()));
    var rows=new ArrayList<Map<String,Object>>();long total=-1;var ids=new HashSet<String>();
    for(int page=1;page<=4;page++) {
      progress.update(rows.size(),total<0?-1:Math.min(400,total));
      input.put("page",page);
      if(total>=0)input.put("total",total);
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
        if(!ids.add(row.get("media_account_id")+":"+row.get("promotion_id")))throw new IllegalArgumentException("incomplete");
        rows.add(row);
      }
      progress.update(rows.size(),Math.min(400,total));
      if(rows.size()>=Math.min(400,total))break;
    }
    return BidSnapshotController.validate(Map.of("date",today.toString(),"rows",rows,"selection","spend_desc_top_400",
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
