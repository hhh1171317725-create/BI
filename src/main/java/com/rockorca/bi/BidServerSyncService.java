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
  private static final long INTERVAL_MILLIS=600000L;
  private static final int PAGE_CONCURRENCY=4;
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
    for (String key:List.of("clientUser","mainUserId","state","error","lastSuccess","dueAt","progress"))
      if (state.containsKey(key)) output.put(key,state.get(key));
    output.put("minutes",10);
    output.put("createdDays",4);
    return output;
  }

  Map<String,Object> start(long owner,Map<String,Object> input) throws Exception {
    return configure(owner,input,false);
  }

  Map<String,Object> prepareQuery(long owner,Map<String,Object> input)throws Exception{
    return configure(owner,input,true);
  }

  private Map<String,Object> configure(long owner,Map<String,Object> input,boolean querying)throws Exception{
    String cookie=BidMonitorApiController.normalizeCookie(text(input,"cookie"));
    if(cookie.length()>16000)throw new IllegalArgumentException("Cookie 过长");
    var saved=store.update(owner,(connection,state)->{
      String user=text(input,"clientUser"),main=text(input,"mainUserId");
      if(user.isBlank())user=text(state,"clientUser");
      if(main.isBlank())main=text(state,"mainUserId");
      if (!user.matches("[0-9]{1,30}")||!main.matches("[0-9]{1,30}"))
        throw new IllegalArgumentException("首次保存请填写 Cookie、client-user 和 main-user-id");
      String value=cookie;
      if(value.isBlank()) {
        if(!state.containsKey("credential"))throw new IllegalArgumentException("首次启用请填写 Cookie");
        if(!user.equals(state.get("clientUser"))||!main.equals(state.get("mainUserId")))
          throw new IllegalArgumentException("更换创量账户时请同时更新 Cookie");
        try { value=cipher.decrypt(owner,text(state,"credential")); }
        catch(Exception error){throw new IllegalArgumentException("保存的凭据无法解密，请重新填写 Cookie");}
      }
      BidMonitorApiController.validateCookieUser(value,user);
      if(!cookie.isBlank()){
        state.put("credential",cipher.encrypt(owner,value));
        state.put("credentialRevision",UUID.randomUUID().toString());
      }
      state.putIfAbsent("credentialRevision",UUID.randomUUID().toString());
      state.put("clientUser",user);state.put("mainUserId",main);
      state.put("minutes",10);state.put("createdDays",4);
      // Manual querying must not postpone an already enabled schedule or invalidate its worker.
      if(!querying||!cookie.isBlank()||!Boolean.TRUE.equals(state.get("enabled"))){
        queue(state);
        if(querying){state.put("dueAt",System.currentTimeMillis()+INTERVAL_MILLIS);state.put("state","ready");}
      }
    });
    var response=view(owner,saved);
    if(querying)response.put("queryRevision",saved.get("credentialRevision"));
    return response;
  }

  Map<String,Object> queryPage(long owner,Map<String,Object> input)throws Exception{
    var state=store.get(owner);
    requireQueryRevision(state,text(input,"queryRevision"));
    String cookie;
    try{cookie=cipher.decrypt(owner,text(state,"credential"));}
    catch(Exception error){throw new IllegalArgumentException("保存的凭据无法解密，请更新登录凭据");}
    var request=new LinkedHashMap<String,Object>();
    for(String key:List.of("startDate","endDate","createdStart","createdEnd","keyword","page","total"))
      if(input.containsKey(key))request.put(key,input.get(key));
    request.put("cookie",cookie);request.put("clientUser",state.get("clientUser"));request.put("mainUserId",state.get("mainUserId"));
    if(!allowed(owner))throw new IllegalStateException("permission");
    var result=upstream.page(request);
    requireQueryRevision(store.get(owner),text(input,"queryRevision"));
    if(!allowed(owner))throw new IllegalStateException("permission");
    return result;
  }

  private static void requireQueryRevision(Map<String,Object> state,String revision){
    if(!state.containsKey("credential")||revision.isBlank()||!revision.equals(state.get("credentialRevision")))
      throw new IllegalArgumentException("查询期间凭据已变更或清除，请重新查询");
  }

  Map<String,Object> querySnapshot(long owner,Map<String,Object> input)throws Exception{
    var state=store.get(owner);String revision=text(input,"queryRevision"),token=text(state,"token");
    requireQueryRevision(state,revision);
    if(!allowed(owner))throw new IllegalStateException("permission");
    if(!current(state,token))throw new IllegalArgumentException("同步已停止，请重新启用查询");
    String cookie;
    try{cookie=cipher.decrypt(owner,text(state,"credential"));}
    catch(Exception error){throw new IllegalArgumentException("保存的凭据无法解密，请更新登录凭据");}
    long startedAt=System.currentTimeMillis();
    store.update(owner,(connection,latest)->{
      requireQueryRevision(latest,revision);
      if(!current(latest,token)||!Objects.equals(state.get("lastSuccess"),latest.get("lastSuccess"))||!allowed(owner))
        throw new IllegalArgumentException("同步状态已变更，请重新查询");
      latest.put("state","running");latest.put("error","");
      latest.put("progress",progressValue(0,-1,startedAt));
    });
    Map<String,Object> snapshot;
    try{
      snapshot=collect(state,cookie,(done,total)->store.update(owner,(connection,latest)->{
        requireQueryRevision(latest,revision);
        if(!current(latest,token)||!Objects.equals(state.get("lastSuccess"),latest.get("lastSuccess"))||!allowed(owner))
          throw new IllegalArgumentException("同步状态已变更，请重新查询");
        latest.put("progress",progressValue(done,total,startedAt));
      }));
    }catch(Exception error){
      store.update(owner,(connection,latest)->{
        if(current(latest,token)&&revision.equals(text(latest,"credentialRevision"))){
          latest.put("state","ready");latest.remove("progress");
        }
      });
      throw error;
    }
    snapshots.initialize();
    var saved=new LinkedHashMap<String,Object>();
    store.update(owner,(connection,latest)->{
      requireQueryRevision(latest,revision);
      if(!current(latest,token)||!Objects.equals(state.get("lastSuccess"),latest.get("lastSuccess"))||!allowed(owner))
        throw new IllegalArgumentException("同步状态已变更，请重新查询");
      var validated=BidSnapshotController.validate(snapshot);
      snapshots.write(connection,owner,validated);
      saved.putAll(validated);
      // Retire overlapping queries/workers before publishing this complete snapshot.
      latest.put("token",UUID.randomUUID().toString());latest.put("lastSuccess",validated.get("updatedAt"));
      latest.put("state","ready");latest.put("error","");latest.remove("progress");
      latest.put("dueAt",System.currentTimeMillis()+INTERVAL_MILLIS);
    });
    return Map.of("userId",Long.toString(owner),"snapshot",saved);
  }

  Map<String,Object> command(long owner,String action) throws Exception {
    var saved=store.update(owner,(connection,state)->{
      if(action.equals("run")) {
        if(!Boolean.TRUE.equals(state.get("enabled")))throw new IllegalArgumentException("请先保存并启用同步");
        if(!"running".equals(state.get("state")))queue(state);
      } else {
        state.put("token",UUID.randomUUID().toString());state.put("enabled",false);
        state.put("dueAt",0L);state.put("state","stopped");state.put("error","");
        if(action.equals("forget")){state.remove("credential");state.remove("credentialRevision");}
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
    return actor!=null&&actor.active()&&actor.admin()&&users.canUseTool(actor,"bidMonitor");
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
        current.put("progress",progressValue(0,-1,System.currentTimeMillis()));
        // A crashed process leaves a short lease; another instance can resume after expiry.
        current.put("dueAt",System.currentTimeMillis()+180000L);
      });
      if(!token.equals(state.get("token")))return;
      if(!allowed(owner))throw new IllegalStateException("permission");
      String cookie;
      try{cookie=cipher.decrypt(owner,text(state,"credential"));}
      catch(Exception error){throw new IllegalStateException("credential");}
      long startedAt=((Number)((Map<?,?>)state.get("progress")).get("startedAt")).longValue();
      var snapshot=collect(state,cookie,(done,total)->store.update(owner,(connection,current)->{
        if(!current(current,token))throw new CancellationException();
        if(!allowed(owner))throw new IllegalStateException("permission");
        current.put("dueAt",System.currentTimeMillis()+180000L);
        current.put("progress",progressValue(done,total,startedAt));
      }));
      snapshots.initialize();
      store.update(owner,(connection,current)->{
        if(!current(current,token))return;
        if(!allowed(owner))throw new IllegalStateException("permission");
        // Revalidate at commit time to reject a request which crossed Beijing midnight.
        var validated=BidSnapshotController.validate(snapshot);
        snapshots.write(connection,owner,validated);
        current.put("lastSuccess",validated.get("updatedAt"));current.put("state","ready");
        current.put("minutes",10);current.put("error","");current.remove("progress");
        current.put("dueAt",System.currentTimeMillis()+INTERVAL_MILLIS);
      });
    } catch(Exception error) {
      try {
        store.update(owner,(connection,current)->{
          if(!current(current,token))return;
          boolean pause=requiresAttention(error);
          current.put("enabled",!pause);current.put("dueAt",pause?0L:System.currentTimeMillis()+INTERVAL_MILLIS);
          current.put("state",pause?"paused":"retrying");
          current.put("error",pause?failure(error):"本次查询失败，已保留旧数据；10 分钟后自动重试，无需重复填写凭据");
          current.remove("progress");
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
  private record PageChunk(long total,List<Map<String,Object>> rows) {}
  static Map<String,Object> progressValue(int done,long total,long startedAt){
    return Map.of("done",done,"total",total,"startedAt",startedAt,"updatedAt",System.currentTimeMillis());
  }
  static LocalDate creationStart(LocalDate today){return today.minusDays(3);}

  Map<String,Object> collect(Map<String,Object> state,String cookie,Progress progress) throws Exception {
    LocalDate today=LocalDate.now(ReportService.BEIJING);
    String start=creationStart(today).toString();
    var input=new LinkedHashMap<String,Object>(Map.of("cookie",cookie,"clientUser",state.get("clientUser"),
        "mainUserId",state.get("mainUserId"),"startDate",today.toString(),"endDate",today.toString(),
        "createdStart",start,"createdEnd",today.toString()));
    var rows=new ArrayList<Map<String,Object>>();long received=0,duplicates=0;var ids=new HashSet<String>();
    progress.update(0,-1);
    PageChunk first=fetchPage(input,1,-1),chunk=first;long total=first.total();
    int pages=(int)((total+BidMonitorApiController.PAGE_SIZE-1)/BidMonitorApiController.PAGE_SIZE);
    for(var row:chunk.rows()){if(ids.add(row.get("media_account_id")+":"+row.get("promotion_id")))rows.add(row);else duplicates++;}
    received+=chunk.rows().size();progress.update((int)received,total);
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
      for(int firstPage=2;firstPage<=pages;firstPage+=PAGE_CONCURRENCY){
        int lastPage=Math.min(pages,firstPage+PAGE_CONCURRENCY-1);
        var pending=new ArrayList<Future<PageChunk>>();
        for(int page=firstPage;page<=lastPage;page++){
          int requestedPage=page;pending.add(executor.submit(()->fetchPage(input,requestedPage,total)));
        }
        for(Future<PageChunk> future:pending){
          try{chunk=future.get();}
          catch(ExecutionException error){
            if(error.getCause() instanceof Exception cause)throw cause;
            throw new IllegalStateException("分页查询失败",error.getCause());
          }
          for(var row:chunk.rows()){if(ids.add(row.get("media_account_id")+":"+row.get("promotion_id")))rows.add(row);else duplicates++;}
          received+=chunk.rows().size();progress.update((int)received,total);
        }
      }
    }
    return BidSnapshotController.validate(Map.of("date",today.toString(),"rows",rows,"selection","created_window_all",
        "upstreamTotal",rows.size(),"sourceTotal",total,"duplicateRows",duplicates,
        "createdStart",start,"createdEnd",today.toString()));
  }

  private PageChunk fetchPage(Map<String,Object> base,int page,long expectedTotal)throws Exception{
    if(page<1||page>BidMonitorApiController.MAX_PLAN_ROWS/BidMonitorApiController.PAGE_SIZE)
      throw new IllegalArgumentException("计划总数超过 100000 条，请缩小计划创建日期范围");
    var input=new LinkedHashMap<>(base);input.put("page",page);if(expectedTotal>=0)input.put("total",expectedTotal);
    var result=upstream.page(input);long total;
    try{total=Long.parseLong(String.valueOf(result.get("total")));}
    catch(Exception error){throw new IllegalArgumentException("第 "+page+" 页缺少有效的计划总数");}
    if(total<1)throw new IllegalArgumentException("查询范围内没有计划，保留原有结果");
    if(total>BidMonitorApiController.MAX_PLAN_ROWS)
      throw new IllegalArgumentException("计划总数 "+total+" 超过 100000 条，请缩小计划创建日期范围");
    if(expectedTotal>=0&&expectedTotal!=total)
      throw new IllegalArgumentException("分页期间计划总数从 "+expectedTotal+" 变为 "+total+"，请重新查询");
    if(!(result.get("rows") instanceof List<?> batch))throw new IllegalArgumentException("第 "+page+" 页缺少计划列表");
    long expected=Math.min(BidMonitorApiController.PAGE_SIZE,Math.max(0,total-(long)(page-1)*BidMonitorApiController.PAGE_SIZE));
    if(batch.size()!=expected)throw new IllegalArgumentException("第 "+page+" 页数据不完整：应有 "+expected+" 条，实际 "+batch.size()+" 条");
    var rows=new ArrayList<Map<String,Object>>(batch.size());
    for(Object item:batch){
      if(!(item instanceof Map<?,?> raw))throw new IllegalArgumentException("第 "+page+" 页包含格式异常的计划");
      var row=new LinkedHashMap<String,Object>();
      for(String key:List.of("promotion_id","promotion_name","advertiser_id","media_account_id","user_name","promotion_create_time",
          "stat_cost","convert_cnt","active_register","cpa_bid","app_type_text","deep_bid_type_text","deep_cpabid",
          "deep_external_action_text","external_action_text","status_text"))row.put(key,raw.get(key));
      for(String key:List.of("promotion_id","advertiser_id","media_account_id")){
        Object id=row.get(key);
        if(id instanceof Float||id instanceof Double)throw new IllegalArgumentException("第 "+page+" 页的账户或计划 ID 精度异常");
        if(id!=null)row.put(key,id.toString());
      }
      Object name=raw.get("media_account_name");
      row.put("media_account_name",name==null||name.toString().isBlank()?raw.get("advertiser_nick"):name);rows.add(row);
    }
    return new PageChunk(total,rows);
  }

  static String failure(Exception error) {
    if("permission".equals(error.getMessage()))return "网站账户已停用或工具权限已撤销，同步已暂停";
    if("credential".equals(error.getMessage()))return "保存的凭据无法解密，请更新登录凭据后重新启用";
    // Do not persist upstream bodies, cookies, or exception stacks in user-visible status.
    String message=Objects.toString(error.getMessage(),"");
    var code=java.util.regex.Pattern.compile("code=([0-9-]{1,10})").matcher(message);
    return "服务器同步失败"+(code.find()?"（创量 code="+code.group(1)+"）":"")
        +"，已暂停并保留旧快照。请核对有效登录凭据、接口权限及网络后重新启用；不会绕过验证。";
  }

  static boolean requiresAttention(Exception error){
    String message=Objects.toString(error.getMessage(),"");
    return message.equals("permission")||message.equals("credential")||message.contains("code=")
        ||message.contains("HTTP 401")||message.contains("HTTP 403")||message.contains("返回的不是 JSON");
  }

  private static String text(Map<String,Object> data,String key){return Objects.toString(data.get(key),"").trim();}
  @PreDestroy public void close(){workers.shutdownNow();}
}
