package com.rockorca.bi;

import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class BidDingtalkService {
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(BidDingtalkService.class);
  private final BidServerSyncStore store;
  private final BidCredentialCipher cipher;
  private final BidSnapshotController snapshots;
  private final BidServerSyncService sync;
  private final DingtalkRobotClient robot;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ExecutorService workers=Executors.newFixedThreadPool(2);
  private final Semaphore slots=new Semaphore(2);

  @org.springframework.beans.factory.annotation.Autowired
  public BidDingtalkService(BidServerSyncStore store,BidCredentialCipher cipher,BidSnapshotController snapshots,
      BidServerSyncService sync,DingtalkRobotClient robot,ObjectMapper mapper){
    this(store,cipher,snapshots,sync,robot,mapper,Clock.systemUTC());
  }
  BidDingtalkService(BidServerSyncStore store,BidCredentialCipher cipher,BidSnapshotController snapshots,
      BidServerSyncService sync,DingtalkRobotClient robot,ObjectMapper mapper,Clock clock){
    this.store=store;this.cipher=cipher;this.snapshots=snapshots;this.sync=sync;this.robot=robot;this.mapper=mapper;this.clock=clock;
  }
  private static String text(Map<String,Object> state,String key){return Objects.toString(state.get(key),"");}
  private static long number(Map<String,Object> state,String key){return ((Number)state.getOrDefault(key,0L)).longValue();}
  private static List<String> tasks(Map<String,Object> state){
    return state.get("dingTasks") instanceof List<?> list?list.stream().map(Object::toString).toList():List.of();
  }
  private static List<Map<String,Object>> rules(Map<String,Object> state){return BidServerSyncService.validateRules(state.getOrDefault("taskRules",List.of()));}
  static long nextDue(Instant now,String time){
    var current=now.atZone(ReportService.BEIJING);var next=current.toLocalDate().atTime(LocalTime.parse(time)).atZone(ReportService.BEIJING);
    if(!next.toInstant().isAfter(now))next=next.plusDays(1);
    return next.toInstant().toEpochMilli();
  }
  Map<String,Object> settings(long owner)throws Exception{return view(owner,store.get(owner));}
  private Map<String,Object> view(long owner,Map<String,Object> state){
    var result=new LinkedHashMap<String,Object>();
    result.put("userId",Long.toString(owner));result.put("configured",state.containsKey("dingCredential"));
    result.put("enabled",Boolean.TRUE.equals(state.get("dingEnabled")));result.put("time",state.getOrDefault("dingTime","18:00"));
    result.put("keyword",state.getOrDefault("dingKeyword",""));result.put("tasks",tasks(state));
    result.put("availableTasks",rules(state).stream().map(r->r.get("name")).toList());
    result.put("revision",state.getOrDefault("dingRevision",""));result.put("pricingRevision",state.getOrDefault("pricingRevision",""));
    result.put("dueAt",number(state,"dingDueAt"));result.put("lastAttempt",number(state,"dingAttemptAt"));
    result.put("lastResult",state.getOrDefault("dingResult","尚未发送"));
    String status=text(state,"dingState");
    if(status.equals("sending")&&clock.millis()-number(state,"dingAttemptAt")>300000){status="uncertain";result.put("lastResult","上次推送结果未确认，请检查群消息；不会自动补发");}
    result.put("state",status);return result;
  }
  private Map<String,String> credential(long owner,Map<String,Object> state){
    if(!state.containsKey("dingCredential"))return new LinkedHashMap<>();
    try{
      String value=cipher.decrypt(owner,text(state,"dingCredential"));
      if(!value.startsWith("bid-dingtalk\n"))throw new IllegalArgumentException();
      return mapper.readValue(value.substring(13),new TypeReference<Map<String,String>>(){});
    }catch(Exception error){throw new IllegalArgumentException("钉钉配置无法解密，请清除后重新保存机器人配置");}
  }
  Map<String,Object> save(long owner,Map<String,Object> input)throws Exception{
    String time=text(input,"time"),keyword=text(input,"keyword").trim();
    if(!time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw new IllegalArgumentException("请填写有效推送时间（北京时间）");
    if(keyword.length()>64||keyword.contains("\n")||keyword.contains("\r"))throw new IllegalArgumentException("安全关键词最多 64 字且不能换行");
    if(!(input.get("tasks") instanceof List<?> raw)||raw.isEmpty()||raw.size()>10)throw new IllegalArgumentException("请选择 1 至 10 个任务");
    var chosen=raw.stream().map(Object::toString).toList();
    if(new HashSet<>(chosen).size()!=chosen.size())throw new IllegalArgumentException("任务不能重复");
    return view(owner,store.update(owner,(connection,state)->{
      if(!text(input,"revision").equals(text(state,"dingRevision"))||!text(input,"pricingRevision").equals(text(state,"pricingRevision")))
        throw new IllegalArgumentException("配置已变更，请重新读取推送配置后保存");
      var names=rules(state).stream().map(r->r.get("name")).toList();
      if(!names.containsAll(chosen))throw new IllegalArgumentException("所选任务已删除或更名，请重新读取");
      var saved=credential(owner,state);
      String webhook=text(input,"webhook").trim(),secret=text(input,"secret").trim();
      boolean clear=Boolean.TRUE.equals(input.get("clearSecret"));
      if(webhook.isBlank())webhook=saved.getOrDefault("webhook","");
      if(!saved.isEmpty()&&!webhook.equals(saved.get("webhook"))&&secret.isBlank()&&!clear)
        throw new IllegalArgumentException("更换机器人请填写新加签密钥，或勾选不使用加签");
      if(secret.isBlank()&&!clear)secret=saved.getOrDefault("secret","");
      if(clear)secret="";
      DingtalkRobotClient.validate(webhook,secret);
      state.put("dingCredential",cipher.encrypt(owner,"bid-dingtalk\n"+mapper.writeValueAsString(Map.of("webhook",webhook,"secret",secret))));
      state.put("dingKeyword",keyword);state.put("dingTasks",chosen);state.put("dingTime",time);
      boolean enabled=Boolean.TRUE.equals(input.get("enabled"));state.put("dingEnabled",enabled);
      state.put("dingDueAt",enabled?nextDue(clock.instant(),time):0L);
      state.put("dingRevision",UUID.randomUUID().toString());state.put("dingToken",UUID.randomUUID().toString());
      state.put("dingState","saved");
    }));
  }
  Map<String,Object> forget(long owner)throws Exception{
    return view(owner,store.update(owner,(connection,state)->{
      state.remove("dingCredential");state.put("dingEnabled",false);state.put("dingDueAt",0L);
      state.put("dingToken",UUID.randomUUID().toString());state.put("dingRevision",UUID.randomUUID().toString());state.put("dingState","stopped");
    }));
  }
  static void fresh(Map<String,Object> snapshot,Instant now){
    try{
      Instant updated=Instant.parse(snapshot.get("updatedAt").toString());
      if(!snapshot.get("date").toString().equals(now.atZone(ReportService.BEIJING).toLocalDate().toString())
          ||updated.isBefore(now.minusSeconds(1200))||updated.isAfter(now.plusSeconds(60))
          ||!"spend_desc_top_400".equals(snapshot.get("selection")))throw new IllegalArgumentException();
    }catch(Exception error){throw new IllegalArgumentException("没有当天最近 20 分钟的前400条快照，请先完成服务器同步；本次不发送旧数据");}
  }
  private List<Map<String,String>> prepare(long owner,Map<String,Object> state)throws Exception{
    if(!sync.allowed(owner))throw new IllegalArgumentException("网站账户已停用或无出价监测权限");
    if(tasks(state).isEmpty())throw new IllegalArgumentException("请先选择任务并保存推送配置");
    var snapshot=snapshots.readOwned(owner);fresh(snapshot,clock.instant());
    return BidTop5Formatter.messages(snapshot,rules(state),tasks(state)).stream().map(message->{
      var result=new LinkedHashMap<>(message);
      result.put("text",DingtalkRobotClient.content(text(state,"dingKeyword"),message.get("text")));
      return (Map<String,String>)result;
    }).toList();
  }
  Map<String,Object> preview(long owner)throws Exception{
    var state=store.get(owner);var messages=prepare(owner,state);
    boolean missing=messages.stream().anyMatch(m->Boolean.parseBoolean(m.get("missingOptimizer"))||Boolean.parseBoolean(m.get("missingAccountId")));
    return Map.of("userId",Long.toString(owner),"messages",messages,"warning",missing?
        "预览中有计划缺少优化师或平台账户 ID。请查询当天数据或点击立即同步，等待快照保存成功后重新预览；不使用创量内部 ID 代替平台账户 ID。":"");
  }
  private void checkCurrent(long owner,String token,String pricingRevision)throws Exception{
    var state=store.get(owner);
    if(!token.equals(state.get("dingToken"))||!pricingRevision.equals(text(state,"pricingRevision"))||!sync.allowed(owner))
      throw new IllegalArgumentException("推送配置、任务价格或账户权限已变更，停止后续发送");
  }
  Map<String,Object> send(long owner,boolean scheduled)throws Exception{
    String token=UUID.randomUUID().toString(),day=clock.instant().atZone(ReportService.BEIJING).toLocalDate().toString();
    var claimed=store.update(owner,(connection,state)->{
      if(scheduled){
        if(!Boolean.TRUE.equals(state.get("dingEnabled"))||number(state,"dingDueAt")<=0||number(state,"dingDueAt")>clock.millis())return;
        var todayAt=clock.instant().atZone(ReportService.BEIJING).toLocalDate().atTime(LocalTime.parse(text(state,"dingTime"))).atZone(ReportService.BEIJING).toInstant();
        if(clock.instant().isBefore(todayAt)||clock.instant().isAfter(todayAt.plusSeconds(1800))){
          state.put("dingDueAt",nextDue(clock.instant(),text(state,"dingTime")));return;
        }
        if(number(state,"dingAttemptAt")>0&&clock.millis()-number(state,"dingAttemptAt")<300000){
          state.put("dingDueAt",number(state,"dingAttemptAt")+300000);return;
        }
        state.put("dingDueAt",nextDue(clock.instant(),text(state,"dingTime")));
        if(day.equals(state.get("dingDay")))return;
        state.put("dingDay",day);
      }else if(number(state,"dingAttemptAt")>0&&clock.millis()-number(state,"dingAttemptAt")<300000)
        throw new IllegalArgumentException("距上次推送不足 5 分钟，请先检查群消息，避免重复发送");
      // Claim before network I/O: ambiguous deliveries are never retried automatically that day.
      state.put("dingToken",token);state.put("dingAttemptAt",clock.millis());state.put("dingState","sending");state.put("dingResult","准备推送");
    });
    if(!token.equals(claimed.get("dingToken")))return view(owner,claimed);
    int delivered=0;
    try{
      var messages=prepare(owner,claimed);var credentials=credential(owner,claimed);
      DingtalkRobotClient.validate(credentials.getOrDefault("webhook",""),credentials.getOrDefault("secret",""));
      for(var message:messages){
        checkCurrent(owner,token,text(claimed,"pricingRevision"));
        robot.send(credentials.get("webhook"),credentials.getOrDefault("secret",""),text(claimed,"dingKeyword"),message.get("text"));
        int count=++delivered;
        store.update(owner,(connection,state)->{if(token.equals(state.get("dingToken")))state.put("dingResult","已发送 "+count+" 条消息，包含 "+tasks(claimed).size()+" 个任务");});
      }
      store.update(owner,(connection,state)->{if(token.equals(state.get("dingToken")))state.put("dingState","sent");});
    }catch(Exception error){
      String detail=error instanceof IllegalArgumentException?error.getMessage():"读取配置或发送结果未确认，请检查服务器及群消息";
      int count=delivered;
      store.update(owner,(connection,state)->{if(token.equals(state.get("dingToken"))){state.put("dingState","failed");state.put("dingResult","已发送 "+count+" 条消息；"+detail+"。本轮不会自动补发");}});
    }
    return settings(owner);
  }
  @Scheduled(fixedDelay=15000,initialDelay=20000)
  public void dispatch(){
    try{
      for(long owner:store.dingtalkDue(clock.millis())){
        if(!slots.tryAcquire())break;
        try{workers.submit(()->{try{send(owner,true);}catch(Exception ignored){LOG.warn("Bid notification could not persist delivery state for user {}",owner);}finally{slots.release();}});}
        catch(RejectedExecutionException error){slots.release();}
      }
    }catch(Exception ignored){LOG.warn("Bid notification scheduler could not read due jobs; retrying later");}
  }
  @PreDestroy public void close(){workers.shutdownNow();}
}
