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
  private final GdtBidMonitorClient gdt;
  private final BidSnapshotController snapshots;
  private final BidProviderRawStore rawStore;
  private final UserService users;
  private final ExecutorService workers=Executors.newFixedThreadPool(2);
  private final Semaphore slots=new Semaphore(2);

  public BidServerSyncService(BidServerSyncStore store,BidCredentialCipher cipher,
      BidMonitorApiController upstream,GdtBidMonitorClient gdt,BidSnapshotController snapshots,
      BidProviderRawStore rawStore,UserService users) {
    this.store=store;this.cipher=cipher;this.upstream=upstream;this.gdt=gdt;this.snapshots=snapshots;this.rawStore=rawStore;this.users=users;
  }

  Map<String,Object> status(long owner) throws Exception { return view(owner,store.get(owner)); }

  Map<String,Object> pricing(long owner)throws Exception{return pricingView(owner,store.get(owner));}

  Map<String,Object> strategies(long owner)throws Exception{return strategyView(owner,store.get(owner));}

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

  private static Map<String,Object> strategyView(long owner,Map<String,Object> state){
    return Map.of("userId",Long.toString(owner),"strategies",state.getOrDefault("strategies",List.of()),
        "revision",state.getOrDefault("strategyRevision",""));
  }

  Map<String,Object> saveStrategies(long owner,Map<String,Object> input)throws Exception{
    var strategies=validateStrategies(input.get("strategies"));
    return strategyView(owner,store.update(owner,(connection,state)->{
      if(!text(input,"revision").equals(text(state,"strategyRevision")))
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"测试策略已在其他页面修改，请重新读取");
      state.put("strategies",strategies);state.put("strategyRevision",UUID.randomUUID().toString());
    }));
  }

  static List<Map<String,Object>> validateStrategies(Object input){
    if(!(input instanceof List<?> list)||list.size()>30)throw new IllegalArgumentException("最多配置 30 个测试策略");
    var result=new ArrayList<Map<String,Object>>();var names=new HashSet<String>();var ids=new HashSet<String>();
    for(Object item:list){
      if(!(item instanceof Map<?,?> raw))throw new IllegalArgumentException("测试策略格式无效");
      String id=Objects.toString(raw.get("id"),"").trim(),name=Objects.toString(raw.get("name"),"").trim(),note=Objects.toString(raw.get("note"),"").trim();
      if(id.isBlank())id=UUID.randomUUID().toString();
      if(!id.matches("[A-Za-z0-9_-]{1,80}")||!ids.add(id)||name.isBlank()||name.length()>60||note.length()>500||!names.add(name.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("策略名称须为 1 至 60 字且不能重复，说明不能超过 500 字");
      if(!(raw.get("accounts") instanceof List<?> accounts)||accounts.size()>200)throw new IllegalArgumentException("每个策略最多关联 200 个账户");
      var savedAccounts=new ArrayList<Map<String,Object>>();var keys=new HashSet<String>();
      for(Object account:accounts){
        if(!(account instanceof Map<?,?> value))throw new IllegalArgumentException("策略账户格式无效");
        String key=Objects.toString(value.get("key"),"").trim(),label=Objects.toString(value.get("label"),"").trim(),accountId=Objects.toString(value.get("accountId"),"").trim(),platform=Objects.toString(value.get("platform"),"").trim();
        if(key.isBlank()||key.length()>300||label.isBlank()||label.length()>160||accountId.length()>80||platform.length()>40||!keys.add(key))
          throw new IllegalArgumentException("策略账户信息无效或重复");
        savedAccounts.add(Map.of("key",key,"label",label,"accountId",accountId,"platform",platform));
      }
      var saved=new LinkedHashMap<String,Object>();saved.put("id",id);saved.put("name",name);saved.put("note",note);saved.put("accounts",savedAccounts);result.add(saved);
    }
    return result;
  }

  static List<Map<String,Object>> validateRules(Object input){
    if(!(input instanceof List<?> list)||list.size()>50)throw new IllegalArgumentException("最多配置 50 个任务");
    var result=new ArrayList<Map<String,Object>>();var names=new HashSet<String>();var keywords=new HashSet<String>();
    for(Object item:list){
      if(!(item instanceof Map<?,?> raw))throw new IllegalArgumentException("任务格式无效");
      String name=Objects.toString(raw.get("name"),"").trim(),keyword=Objects.toString(raw.get("keyword"),"").trim();
      String urlKeyword=Objects.toString(raw.get("urlKeyword"),"").trim();
      if(name.isBlank()||name.length()>80||keyword.isBlank()||keyword.length()>80||
          !names.add(name.toLowerCase(Locale.ROOT))||!keywords.add(keyword.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("任务名及账户关键词须为 1 至 80 字，且不能重复");
      if(urlKeyword.length()>200)throw new IllegalArgumentException("URL 特征不能超过 200 字");
      String configuredPrice=Objects.toString(raw.get("price"),"").trim();
      if(configuredPrice.isBlank()){
        result.add(Map.of("name",name,"keyword",keyword,"urlKeyword",urlKeyword,"price",""));
        continue;
      }
      java.math.BigDecimal price;
      try{price=new java.math.BigDecimal(configuredPrice);}
      catch(NumberFormatException error){throw new IllegalArgumentException("请填写有效结算单价");}
      if(price.signum()<=0||price.compareTo(new java.math.BigDecimal("1000000"))>0||price.scale()>6)
        throw new IllegalArgumentException("结算单价须大于 0、不超过 1000000，最多 6 位小数");
      result.add(Map.of("name",name,"keyword",keyword,"urlKeyword",urlKeyword,"price",price.toPlainString()));
    }
    return result;
  }

  static Map<String,Object> view(long owner,Map<String,Object> state) {
    var output=new LinkedHashMap<String,Object>();
    output.put("userId",Long.toString(owner));
    output.put("configured",state.containsKey("credential"));
    output.put("enabled",Boolean.TRUE.equals(state.get("enabled")));
    for (String key:List.of("clientUser","mainUserId","state","error","lastSuccess","dueAt","progress",
        "historyState","historyError","historyLastDate","historyLastSuccess","historyRetryAt"))
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
    String platform=text(input,"platform");
    var result="gdt".equals(platform)?gdt.page(request):upstream.page(request);
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
    snapshots.initialize();rawStore.initialize();
    var saved=new LinkedHashMap<String,Object>();
    store.update(owner,(connection,latest)->{
      requireQueryRevision(latest,revision);
      if(!current(latest,token)||!Objects.equals(state.get("lastSuccess"),latest.get("lastSuccess"))||!allowed(owner))
        throw new IllegalArgumentException("同步状态已变更，请重新查询");
      var validated=BidSnapshotController.validate(snapshot);
      rawStore.replace(connection,owner,String.valueOf(snapshot.get("date")),rawRows(snapshot));
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
      snapshots.initialize();rawStore.initialize();
      store.update(owner,(connection,current)->{
        if(!current(current,token))return;
        if(!allowed(owner))throw new IllegalStateException("permission");
        // Revalidate at commit time to reject a request which crossed Beijing midnight.
        var validated=BidSnapshotController.validate(snapshot);
        rawStore.replace(connection,owner,String.valueOf(snapshot.get("date")),rawRows(snapshot));
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
  private record SourceRows(long total,long duplicates,List<Map<String,Object>> rows) {}
  static Map<String,Object> progressValue(int done,long total,long startedAt){
    return Map.of("done",done,"total",total,"startedAt",startedAt,"updatedAt",System.currentTimeMillis());
  }
  static LocalDate creationStart(LocalDate today){return today.minusDays(3);}

  Map<String,Object> collect(Map<String,Object> state,String cookie,Progress progress) throws Exception {
    LocalDate today=LocalDate.now(ReportService.BEIJING);
    return collectWindow(state,cookie,today,creationStart(today),today,progress,false);
  }

  Map<String,Object> collectHistory(Map<String,Object> state,String cookie,LocalDate runDate)throws Exception{
    LocalDate reportDate=runDate.minusDays(1);
    return collectWindow(state,cookie,reportDate,runDate.minusDays(4),reportDate,(done,total)->{},true);
  }

  /** Requery cumulative metrics, including conversion backfills and activity after the warning window. */
  List<Map<String,Object>> collectPlanTotals(Map<String,Object> state,String cookie,List<Map<String,Object>> candidates,LocalDate today)throws Exception{
    return collectPlanTotals(state,cookie,candidates,today,message->{});
  }
  List<Map<String,Object>> collectPlanTotals(Map<String,Object> state,String cookie,List<Map<String,Object>> candidates,LocalDate today,java.util.function.Consumer<String> progress)throws Exception{
    return collectPlanTotals(state,cookie,candidates,today,progress,rows->{});
  }
  List<Map<String,Object>> collectPlanTotals(Map<String,Object> state,String cookie,List<Map<String,Object>> candidates,LocalDate today,
      java.util.function.Consumer<String> progress,java.util.function.Consumer<List<Map<String,Object>>> verified)throws Exception{
    var groups=new TreeMap<String,List<Map<String,Object>>>();
    for(var row:candidates){
      var created=BidEndedWarning.date(row.get("promotion_create_time"));String platform=text(row,"source_platform");
      if(created==null||!List.of("byte","gdt").contains(platform))continue;
      groups.computeIfAbsent(platform+":"+created.toString().substring(0,7),key->new ArrayList<>()).add(row);
    }
    var output=new ArrayList<Map<String,Object>>();int groupIndex=0;
    for(var group:groups.values()){
      String platform=text(group.getFirst(),"source_platform");
      String groupLabel=("gdt".equals(platform)?"广点通":"字节")+" · 第 "+(++groupIndex)+" / "+groups.size()+" 组";
      var first=group.stream().map(r->BidEndedWarning.date(r.get("promotion_create_time"))).min(LocalDate::compareTo).orElseThrow();
      var last=group.stream().map(r->BidEndedWarning.date(r.get("promotion_create_time"))).max(LocalDate::compareTo).orElseThrow();
      String accountField="gdt".equals(platform)?"advertiser_id":"media_account_id";
      var accounts=group.stream().map(r->text(r,accountField)).distinct().toList();
      var wanted=new HashSet<String>();for(var candidate:group)wanted.add(planKey(candidate));
      var totals=new LinkedHashMap<String,Map<String,Object>>();var invalid=new HashSet<String>();
      boolean singleWindow=!first.plusDays(92).isBefore(today);
      for(LocalDate start=first;!start.isAfter(today);start=start.plusDays(93)){
        LocalDate end=start.plusDays(92).isAfter(today)?today:start.plusDays(92);
        var input=new LinkedHashMap<String,Object>(Map.of("cookie",cookie,"clientUser",state.get("clientUser"),"mainUserId",state.get("mainUserId"),
            "startDate",start.toString(),"endDate",end.toString(),"createdStart",first.toString(),"createdEnd",last.toString()));
        input.put("verificationOnly",true);
        // Do not exclude legacy candidates with missing account IDs from verification.
        if(accounts.stream().allMatch(id->id.matches("[0-9]+")))input.put("accountIds",accounts);
        String queryLabel=groupLabel+" · 累计区间 "+start+" 至 "+end;
        progress.accept(queryLabel+" · 正在查询接口…");
        var source=collectVerificationSource(input,platform,wanted,(done,total)->progress.accept(queryLabel+" · 已读取 "+done+" / "+total+" 条"),
            rows->{if(singleWindow)verified.accept(rows);});
        if(source.duplicates()>0)throw new IllegalArgumentException("核验分页存在重复计划，请重试");
        var indexed=new HashMap<String,Map<String,Object>>();for(var row:source.rows())indexed.put(planKey(row),row);
        for(var candidate:group){
          String key=planKey(candidate);
          if(BidEndedWarning.date(candidate.get("promotion_create_time")).isAfter(end))continue;
          var row=indexed.get(key);
          double cost=row==null?Double.NaN:BidEndedWarning.number(row.get("stat_cost")),conversions=row==null?Double.NaN:BidEndedWarning.number(row.get("convert_cnt"));
          if(!Double.isFinite(cost)||cost<0||!Double.isFinite(conversions)||conversions<0){invalid.add(key);continue;}
          var prior=totals.get(key);var total=new LinkedHashMap<String,Object>(row);total.remove("provider_data");
          total.put("stat_cost",cost+(prior==null?0:((Number)prior.get("stat_cost")).doubleValue()));
          total.put("convert_cnt",conversions+(prior==null?0:((Number)prior.get("convert_cnt")).doubleValue()));
          totals.put(key,total);
        }
      }
      var complete=new ArrayList<Map<String,Object>>();totals.forEach((key,row)->{if(!invalid.contains(key))complete.add(row);});
      output.addAll(complete);if(!singleWindow)verified.accept(complete);
    }
    return output;
  }

  /** Only warning verification may finish early; daily snapshots still read every page. */
  private SourceRows collectVerificationSource(Map<String,Object> input,String platform,Set<String> wanted,Progress progress,
      java.util.function.Consumer<List<Map<String,Object>>> verified)throws Exception{
    var rows=new ArrayList<Map<String,Object>>();var found=new HashSet<String>();
    PageChunk chunk=fetchPage(input,platform,1,-1);long total=chunk.total(),received=0,duplicates=0;
    int pages=(int)((total+BidMonitorApiController.PAGE_SIZE-1)/BidMonitorApiController.PAGE_SIZE),nextPage=2;
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
      var completed=new ExecutorCompletionService<PageChunk>(executor);
      var pending=new HashSet<Future<PageChunk>>();
      try{
        while(true){
          var batch=new ArrayList<Map<String,Object>>();
          for(var row:chunk.rows())if(wanted.contains(planKey(row))){
            if(found.add(planKey(row))){row.remove("provider_data");rows.add(row);batch.add(row);}else duplicates++;
          }
          received+=chunk.rows().size();progress.update((int)received,total);
          if(duplicates==0&&!batch.isEmpty())verified.accept(List.copyOf(batch));
          if(found.size()==wanted.size()||duplicates>0)break;
          // Refill each free slot immediately; one slow page cannot stall the next batch.
          while(nextPage<=pages&&pending.size()<PAGE_CONCURRENCY){
            int page=nextPage++;pending.add(completed.submit(()->fetchPage(input,platform,page,total)));
          }
          if(pending.isEmpty())break;
          var future=completed.take();pending.remove(future);
          try{chunk=future.get();}
          catch(ExecutionException error){
            if(error.getCause() instanceof Exception cause)throw cause;
            throw new IllegalStateException("分页查询失败",error.getCause());
          }
        }
      }finally{for(var future:pending)future.cancel(true);}
    }
    return new SourceRows(total,duplicates,rows);
  }

  private Map<String,Object> collectWindow(Map<String,Object> state,String cookie,LocalDate reportDate,
      LocalDate createdStart,LocalDate createdEnd,Progress progress,boolean allowEmpty)throws Exception{
    String start=createdStart.toString();
    var input=new LinkedHashMap<String,Object>(Map.of("cookie",cookie,"clientUser",state.get("clientUser"),
        "mainUserId",state.get("mainUserId"),"startDate",reportDate.toString(),"endDate",reportDate.toString(),
        "createdStart",start,"createdEnd",createdEnd.toString()));
    var rows=new ArrayList<Map<String,Object>>();long sourceTotal=0,duplicates=0;var ids=new HashSet<String>();
    progress.update(0,-1);
    for(String platform:List.of("byte","gdt")){
      SourceRows source=collectSource(input,platform,ids,rows.size(),sourceTotal,progress);
      rows.addAll(source.rows());sourceTotal+=source.total();duplicates+=source.duplicates();
      if(rows.size()>BidMonitorApiController.MAX_PLAN_ROWS)
        throw new IllegalArgumentException("字节与广点通计划合计超过 100000 条，请缩小计划创建日期范围");
    }
    if(rows.isEmpty()&&!allowEmpty)throw new IllegalArgumentException("查询范围内没有字节或广点通计划，保留原有结果");
    return new LinkedHashMap<>(Map.of("date",reportDate.toString(),"rows",rows,"selection","created_window_all",
        "upstreamTotal",(long)rows.size(),"sourceTotal",sourceTotal,"duplicateRows",duplicates,
        "createdStart",start,"createdEnd",createdEnd.toString()));
  }

  private SourceRows collectSource(Map<String,Object> input,String platform,Set<String> ids,int completed,long completedTotal,Progress progress)throws Exception{
    var rows=new ArrayList<Map<String,Object>>();long received=0,duplicates=0;
    PageChunk first=fetchPage(input,platform,1,-1),chunk=first;long total=first.total();
    int pages=(int)((total+BidMonitorApiController.PAGE_SIZE-1)/BidMonitorApiController.PAGE_SIZE);
    for(var row:chunk.rows()){if(ids.add(planKey(row)))rows.add(row);else duplicates++;}
    received+=chunk.rows().size();progress.update(completed+(int)received,completedTotal+total);
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
      for(int firstPage=2;firstPage<=pages;firstPage+=PAGE_CONCURRENCY){
        int lastPage=Math.min(pages,firstPage+PAGE_CONCURRENCY-1);
        var pending=new ArrayList<Future<PageChunk>>();
        for(int page=firstPage;page<=lastPage;page++){
          int requestedPage=page;pending.add(executor.submit(()->fetchPage(input,platform,requestedPage,total)));
        }
        for(Future<PageChunk> future:pending){
          try{chunk=future.get();}
          catch(ExecutionException error){
            if(error.getCause() instanceof Exception cause)throw cause;
            throw new IllegalStateException("分页查询失败",error.getCause());
          }
          for(var row:chunk.rows()){if(ids.add(planKey(row)))rows.add(row);else duplicates++;}
          received+=chunk.rows().size();progress.update(completed+(int)received,completedTotal+total);
        }
      }
    }
    return new SourceRows(total,duplicates,rows);
  }

  private static String planKey(Map<String,Object> row){
    return row.get("source_platform")+":"+row.get("media_account_id")+":"+row.get("promotion_id");
  }

  private PageChunk fetchPage(Map<String,Object> base,String platform,int page,long expectedTotal)throws Exception{
    if(page<1||page>BidMonitorApiController.MAX_PLAN_ROWS/BidMonitorApiController.PAGE_SIZE)
      throw new IllegalArgumentException("计划总数超过 100000 条，请缩小计划创建日期范围");
    var input=new LinkedHashMap<>(base);input.put("page",page);if(expectedTotal>=0)input.put("total",expectedTotal);
    var result="gdt".equals(platform)?gdt.page(input):upstream.page(input);long total;
    try{total=Long.parseLong(String.valueOf(result.get("total")));}
    catch(Exception error){throw new IllegalArgumentException("第 "+page+" 页缺少有效的计划总数");}
    if(total<0)throw new IllegalArgumentException("第 "+page+" 页的计划总数无效");
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
          "deep_external_action_text","external_action_text","status_text","show_cnt","cpm_platform","ecpm","open_url","source_platform","platform_text","provider_data"))row.put(key,raw.get(key));
      row.putIfAbsent("source_platform",platform);row.putIfAbsent("platform_text","gdt".equals(platform)?"广点通":"字节");
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

  @SuppressWarnings("unchecked")
  static List<Map<String,Object>> rawRows(Map<String,Object> snapshot){
    return (List<Map<String,Object>>)snapshot.get("rows");
  }

  static String failure(Exception error) {
    if("permission".equals(error.getMessage()))return "网站账户已停用或工具权限已撤销，同步已暂停";
    if("credential".equals(error.getMessage()))return "保存的凭据无法解密，请更新登录凭据后重新启用";
    // Do not persist upstream bodies, cookies, or exception stacks in user-visible status.
    String message=Objects.toString(error.getMessage(),"");
    String platform=message.startsWith("广点通")?"广点通":message.startsWith("创量")?"字节":"";
    var code=java.util.regex.Pattern.compile("code=([0-9-]{1,10})").matcher(message);
    String source=platform.isBlank()?"上游":platform;
    return "服务器同步失败"+(code.find()?"（"+source+" code="+code.group(1)+"）":"")
        +"，已暂停并保留旧快照。请更新创量登录凭据并核对"+source+"接口权限及网络后重新启用。";
  }

  static boolean requiresAttention(Exception error){
    String message=Objects.toString(error.getMessage(),"");
    return message.equals("permission")||message.equals("credential")||message.contains("code=")
        ||message.contains("HTTP 401")||message.contains("HTTP 403")||message.contains("返回的不是 JSON");
  }

  private static String text(Map<String,Object> data,String key){return Objects.toString(data.get(key),"").trim();}
  @PreDestroy public void close(){workers.shutdownNow();}
}
