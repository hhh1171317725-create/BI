package com.rockorca.bi;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Personal Garmin sessions and day caches; secrets travel only through the worker's stdin. */
@Service
public class GarminSyncService {
  private static final Set<PosixFilePermission> DIR_MODE=Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE);
  private static final Set<PosixFilePermission> FILE_MODE=Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);
  private static final List<String> METRICS=List.of("steps","distanceMeters","activeCalories","totalCalories","restingHeartRate",
      "heartRateAverage","heartRateMin","heartRateMax","sleepSeconds","deepSleepSeconds","lightSleepSeconds","remSleepSeconds",
      "awakeSleepSeconds","sleepScore","stress","bodyBattery","hrvMs","weightKg","workoutSeconds");
  private static final Set<String> WARNING_CODES=Set.of("steps","heart_rate","sleep","stress","body_battery","hrv","weight","activities","workout");
  private final RuntimeConfig config;
  private final UserService users;
  private final ObjectMapper mapper;
  private final Path root;
  private final WorkerRunner runner;
  private final Semaphore slots;
  private final long connectTimeout,syncTimeout;
  private final Map<Long,Owner> owners=new ConcurrentHashMap<>();
  private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService timeouts=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("garmin-timeouts").factory());
  private volatile boolean closed;

  interface WorkerRunner { Worker start()throws Exception; boolean available(); }
  interface Worker extends AutoCloseable {
    void send(Map<String,Object> input)throws Exception;
    Map<String,Object> read()throws Exception;
    int awaitExit()throws Exception;
    void cancel();
    @Override default void close(){cancel();}
  }
  private static final class WorkerFailure extends Exception {
    final String code;WorkerFailure(String code){this.code=code;}
  }
  private static final class Owner {
    final long id;final Path dir;Map<String,Object> disk;Job job;long revision;
    Owner(long id,Path dir,Map<String,Object> disk){this.id=id;this.dir=dir;this.disk=disk;}
  }
  private static final class Job {
    final String id=UUID.randomUUID().toString(),action,region,fingerprint,tokenId,dataId;
    final long revision,expiresAt;final LocalDate start,end;
    volatile Worker worker;volatile boolean cancelled;volatile String failure="CANCELLED";
    boolean mfaRequired,invalidCode,completed;int mfaAttempts,done;String method="unknown";
    Job(String action,String region,String fingerprint,String tokenId,String dataId,long revision,long expiresAt,LocalDate start,LocalDate end){
      this.action=action;this.region=region;this.fingerprint=fingerprint;this.tokenId=tokenId;this.dataId=dataId;
      this.revision=revision;this.expiresAt=expiresAt;this.start=start;this.end=end;
    }
  }
  @Autowired
  public GarminSyncService(RuntimeConfig config,UserService users){this(config,users,new ProcessRunner(config),2,300_000,900_000);}
  GarminSyncService(RuntimeConfig config,UserService users,WorkerRunner runner,int concurrency,long connectTimeout,long syncTimeout){
    this.config=config;this.users=users;this.mapper=config.objectMapper();this.root=config.runtimeDir().resolve("garmin").toAbsolutePath().normalize();
    this.runner=runner;this.slots=new Semaphore(concurrency);this.connectTimeout=connectTimeout;this.syncTimeout=syncTimeout;
  }

  private Owner owner(long id){
    if(id<=0)throw problem(HttpStatus.BAD_REQUEST,"无效的网站账户");
    return owners.computeIfAbsent(id,key->{
      Path dir=root.resolve(Long.toString(key));var disk=defaults();
      try{
        if(Files.exists(dir.resolve("state.json")))disk.putAll(mapper.readValue(dir.resolve("state.json").toFile(),Map.class));
        for(String field:List.of("tokenId","dataId"))if(!text(disk,field).isBlank())uuid(text(disk,field));
      }catch(Exception error){throw problem(HttpStatus.INTERNAL_SERVER_ERROR,"佳明本地配置读取失败");}
      return new Owner(key,dir,disk);
    });
  }
  private static LinkedHashMap<String,Object> defaults(){
    return new LinkedHashMap<>(Map.of("connected",false,"region","global","autoSync",false,"intervalHours",1,"days",7,
        "lastSuccess","","lastAttempt",0L,"error","","accountChanged",false));
  }
  private boolean allowed(long id){
    var actor=users.findById(id);return actor!=null&&actor.active()&&users.canUseTool(actor,"garmin");
  }
  private void requireAllowed(long id){if(!allowed(id))throw problem(HttpStatus.FORBIDDEN,"佳明工具权限已关闭或网站账户已停用");}

  Map<String,Object> status(long id){var owner=owner(id);synchronized(owner){return view(owner);}}
  private Map<String,Object> view(Owner owner){
    var disk=owner.disk;var job=owner.job;boolean connected=Boolean.TRUE.equals(disk.get("connected"));
    String error=text(disk,"error"),state=error.isBlank()?(connected?"ready":"idle"):"error";
    if(job!=null&&!job.cancelled)state=job.mfaRequired?"mfa_required":job.action.equals("connect")?"connecting":"syncing";
    boolean available=runner.available();var output=new LinkedHashMap<String,Object>();
    output.put("userId",Long.toString(owner.id));output.put("connected",connected);output.put("region",text(disk,"region"));
    output.put("state",state);output.put("jobId",job==null?"":job.id);output.put("error",error);output.put("lastSuccess",text(disk,"lastSuccess"));
    output.put("accountChanged",Boolean.TRUE.equals(disk.get("accountChanged")));
    output.put("available",available);output.put("setupError",available?"":"服务器尚未配置佳明 Python 同步环境");
    output.put("settings",Map.of("autoSync",Boolean.TRUE.equals(disk.get("autoSync")),"intervalHours",integer(disk.get("intervalHours"),1),"days",integer(disk.get("days"),7)));
    output.put("progress",Map.of("done",job==null?0:job.done,"total",job==null||job.start==null?0:ChronoUnit.DAYS.between(job.start,job.end)+1));
    output.put("mfa",Map.of("required",job!=null&&!job.cancelled&&job.mfaRequired,"invalidCode",job!=null&&job.invalidCode,
        "method",job==null?"unknown":job.method,"expiresAt",job==null?0L:job.expiresAt));
    return output;
  }
  Map<String,Object> connect(long id,Map<String,Object> input){
    requireAllowed(id);String email=field(input,"email",254).strip(),password=field(input,"password",1024),region=region(text(input,"region"));
    if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")||password.isBlank())throw problem(HttpStatus.BAD_REQUEST,"请填写有效的佳明邮箱与密码");
    var owner=owner(id);synchronized(owner){
      var job=new Job("connect",region,fingerprint(email,region),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
          owner.revision,System.currentTimeMillis()+connectTimeout,null,null);
      var request=new LinkedHashMap<String,Object>(Map.of("action","connect","email",email,"password",password,"region",region,
          "sessionDir",session(owner,job.tokenId).toString()));
      begin(owner,job,request);return view(owner);
    }
  }
  Map<String,Object> sync(long id,Map<String,Object> input){
    requireAllowed(id);var owner=owner(id);synchronized(owner){
      if(!Boolean.TRUE.equals(owner.disk.get("connected")))throw problem(HttpStatus.CONFLICT,"请先连接佳明账户");
      var range=range(text(input,"startDate"),text(input,"endDate"),integer(owner.disk.get("days"),7));
      var job=new Job("sync",text(owner.disk,"region"),text(owner.disk,"fingerprint"),text(owner.disk,"tokenId"),UUID.randomUUID().toString(),
          owner.revision,System.currentTimeMillis()+syncTimeout,range[0],range[1]);
      var request=new LinkedHashMap<String,Object>(Map.of("action","sync","region",job.region,"sessionDir",session(owner,job.tokenId).toString(),
          "startDate",job.start.toString(),"endDate",job.end.toString()));
      begin(owner,job,request);return view(owner);
    }
  }
  private void begin(Owner owner,Job job,Map<String,Object> request){
    if(closed)throw problem(HttpStatus.SERVICE_UNAVAILABLE,"佳明同步服务正在停止");
    if(owner.job!=null)throw problem(HttpStatus.CONFLICT,"本账户已有佳明任务正在运行");
    if(!runner.available())throw problem(HttpStatus.SERVICE_UNAVAILABLE,"服务器尚未配置佳明 Python 同步环境");
    if(!slots.tryAcquire())throw problem(HttpStatus.TOO_MANY_REQUESTS,"佳明同步任务繁忙，请稍后重试");
    try{
      var next=new LinkedHashMap<>(owner.disk);next.put("lastAttempt",System.currentTimeMillis());next.put("error","");save(owner,next);
      owner.job=job;workers.submit(()->run(owner,job,request));
    }catch(RuntimeException error){owner.job=null;slots.release();throw error;}
  }
  Map<String,Object> mfa(long id,Map<String,Object> input){
    requireAllowed(id);String code=field(input,"code",8);if(!code.matches("[0-9]{6,8}"))throw problem(HttpStatus.BAD_REQUEST,"请输入 6 至 8 位数字验证码");
    var owner=owner(id);Job job;Worker worker;
    synchronized(owner){
      job=owner.job;
      if(job==null||job.cancelled||!job.mfaRequired||!job.id.equals(text(input,"jobId"))||System.currentTimeMillis()>=job.expiresAt)
        throw problem(HttpStatus.CONFLICT,"验证请求已失效，请重新连接");
      if(job.mfaAttempts>=3)throw problem(HttpStatus.CONFLICT,"验证码尝试次数已用尽，请重新连接");
      job.mfaAttempts++;job.mfaRequired=false;job.invalidCode=false;worker=job.worker;
    }
    try{worker.send(Map.of("action","mfa","code",code));}
    catch(Exception error){job.failure="MFA";job.cancelled=true;cancel(worker);throw problem(HttpStatus.CONFLICT,"验证码提交失败，请重新连接");}
    return status(id);
  }
  Map<String,Object> settings(long id,Map<String,Object> input){
    requireAllowed(id);if(!(input.get("autoSync") instanceof Boolean auto))throw problem(HttpStatus.BAD_REQUEST,"自动同步设置无效");
    int interval=integer(input.get("intervalHours"),0),days=integer(input.get("days"),0);
    if(!List.of(1,24).contains(interval)||days<1||days>90)throw problem(HttpStatus.BAD_REQUEST,"请选择每小时或每天同步，日期范围为 1 至 90 天");
    var owner=owner(id);synchronized(owner){var next=new LinkedHashMap<>(owner.disk);next.put("autoSync",auto);next.put("intervalHours",interval);next.put("days",days);save(owner,next);return view(owner);}
  }
  Map<String,Object> disconnect(long id){
    requireAllowed(id);var owner=owner(id);synchronized(owner){
      owner.revision++;var job=owner.job;
      if(job!=null){job.failure="DISCONNECTED";job.cancelled=true;cancel(job.worker);}
      var next=new LinkedHashMap<>(owner.disk);next.put("connected",false);next.put("autoSync",false);next.put("error","");next.remove("tokenId");save(owner,next);
      if(job==null)delete(owner.dir.resolve("sessions"));return view(owner);
    }
  }
  Map<String,Object> data(long id,String startDate,String endDate){
    var owner=owner(id);synchronized(owner){
      var dates=range(startDate,endDate,integer(owner.disk.get("days"),7));var days=new ArrayList<Map<String,Object>>();var missing=new ArrayList<String>();
      String dataId=text(owner.disk,"dataId");
      try{
        for(LocalDate date=dates[0];!date.isAfter(dates[1]);date=date.plusDays(1)){
          Path path=dataId.isBlank()?null:cache(owner,dataId).resolve(date+".json");
          if(path==null||!Files.isRegularFile(path)){missing.add(date.toString());continue;}
          var day=mapper.readValue(path.toFile(),Map.class);days.add(normalizeDay(date.toString(),day));
        }
      }catch(Exception error){throw problem(HttpStatus.INTERNAL_SERVER_ERROR,"佳明本地数据读取失败，旧缓存已保留");}
      return Map.of("userId",Long.toString(id),"startDate",dates[0].toString(),"endDate",dates[1].toString(),"days",days,"missingDates",missing,"lastSuccess",text(owner.disk,"lastSuccess"));
    }
  }

  private void run(Owner owner,Job job,Map<String,Object> request){
    Worker worker=null;boolean committed=false;
    ScheduledFuture<?> timeout=null;
    try{
      timeout=timeouts.schedule(()->{
        synchronized(owner){if(owner.job!=job||job.completed)return;job.failure="TIMEOUT";job.cancelled=true;}
        cancel(job.worker);
      },Math.max(1,job.expiresAt-System.currentTimeMillis()),TimeUnit.MILLISECONDS);
      synchronized(owner){check(owner,job);directory(owner.dir);directory(owner.dir.resolve("sessions"));directory(owner.dir.resolve("data"));
        if(job.action.equals("connect"))directory(session(owner,job.tokenId));else directory(cache(owner,job.dataId));}
      worker=runner.start();job.worker=worker;if(job.cancelled)throw new WorkerFailure(job.failure);
      worker.send(request);request.remove("password");request.remove("email");
      var seen=new HashSet<String>();boolean terminal=false;
      for(Map<String,Object> event;(event=worker.read())!=null;){
        if(job.cancelled)throw new WorkerFailure(job.failure);
        if(terminal)throw new WorkerFailure("PROTOCOL");
        synchronized(owner){check(owner,job);
          switch(text(event,"type")){
            case "mfa"->{
              if(!job.action.equals("connect")||job.mfaAttempts>=3)throw new WorkerFailure("MFA");
              job.mfaRequired=true;job.invalidCode=Boolean.TRUE.equals(event.get("invalidCode"));
              String method=text(event,"method");job.method=Set.of("email","sms","app").contains(method)?method:"unknown";
            }
            case "connected"->{if(!job.action.equals("connect"))throw new WorkerFailure("PROTOCOL");terminal=true;}
            case "day"->{
              String date=text(event,"date");if(!job.action.equals("sync")||!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new WorkerFailure("PROTOCOL");
              LocalDate day=LocalDate.parse(date);if(day.isBefore(job.start)||day.isAfter(job.end)||!seen.add(date)||!(event.get("data") instanceof Map<?,?> payload))throw new WorkerFailure("PROTOCOL");
              atomic(cache(owner,job.dataId).resolve(date+".json"),normalizeDay(date,payload));job.done=seen.size();
            }
            case "done"->{
              if(!job.action.equals("sync")||!job.start.toString().equals(text(event,"startDate"))||!job.end.toString().equals(text(event,"endDate"))
                  ||integer(event.get("dayCount"),-1)!=ChronoUnit.DAYS.between(job.start,job.end)+1||seen.size()!=ChronoUnit.DAYS.between(job.start,job.end)+1)throw new WorkerFailure("PROTOCOL");
              terminal=true;
            }
            case "error"->throw new WorkerFailure(text(event,"code"));
            default->throw new WorkerFailure("PROTOCOL");
          }
        }
      }
      if(worker.awaitExit()!=0||!terminal)throw new WorkerFailure("WORKER");
      synchronized(owner){
        check(owner,job);var next=new LinkedHashMap<>(owner.disk);
        String oldToken=text(next,"tokenId"),oldData=text(next,"dataId");
        if(job.action.equals("connect")){
          boolean same=job.fingerprint.equals(text(next,"fingerprint")),changed=!text(next,"fingerprint").isBlank()&&!same;
          secureTree(session(owner,job.tokenId));
          Path tokens=session(owner,job.tokenId).resolve("garmin_tokens.json");
          if(!Files.isRegularFile(tokens)||Files.size(tokens)==0)throw new WorkerFailure("PROTOCOL");
          next.put("connected",true);next.put("region",job.region);next.put("tokenId",job.tokenId);next.put("fingerprint",job.fingerprint);
          if(!same||oldData.isBlank())next.put("dataId",job.dataId);
          if(changed)next.put("lastSuccess","");next.put("accountChanged",changed);
        }else{
          String previous=text(next,"dataId");if(!previous.isBlank())copyCache(cache(owner,previous),cache(owner,job.dataId));
          next.put("dataId",job.dataId);next.put("lastSuccess",Instant.now().toString());next.put("accountChanged",false);
        }
        next.put("error","");save(owner,next);committed=true;job.completed=true;
        if(job.action.equals("connect")&&!oldToken.isBlank()&&!oldToken.equals(job.tokenId))delete(session(owner,oldToken));
        if(!oldData.isBlank()&&!oldData.equals(text(next,"dataId")))delete(cache(owner,oldData));
      }
    }catch(Exception error){
      String code=job.cancelled?job.failure:error instanceof WorkerFailure failure?failure.code:error instanceof IOException?"IO":"WORKER";
      synchronized(owner){if(owner.job==job&&owner.revision==job.revision&&!"DISCONNECTED".equals(code)){
        var next=new LinkedHashMap<>(owner.disk);next.put("error",safeError(code));try{save(owner,next);}catch(RuntimeException ignored){owner.disk.put("error",safeError(code));}
      }}
    }finally{
      request.remove("password");request.remove("email");if(timeout!=null)timeout.cancel(false);cancel(worker);
      if(worker!=null)try{worker.awaitExit();}catch(Exception ignored){}
      synchronized(owner){
        if(!committed)delete(job.action.equals("connect")?session(owner,job.tokenId):cache(owner,job.dataId));
        if(!Boolean.TRUE.equals(owner.disk.get("connected")))delete(owner.dir.resolve("sessions"));
        slots.release();
        if(owner.job==job)owner.job=null;
      }
    }
  }
  private void check(Owner owner,Job job)throws WorkerFailure{
    if(owner.job!=job||owner.revision!=job.revision||job.cancelled)throw new WorkerFailure(job.failure);
    if(System.currentTimeMillis()>=job.expiresAt)throw new WorkerFailure("TIMEOUT");
    if(!allowed(owner.id))throw new WorkerFailure("PERMISSION");
  }
  @Scheduled(fixedDelay=60_000,initialDelay=60_000)
  void dispatch(){
    if(closed)return;
    try{
      if(Files.isDirectory(root))try(var dirs=Files.list(root)){dirs.filter(Files::isDirectory).map(path->path.getFileName().toString()).filter(name->name.matches("[1-9][0-9]{0,18}"))
          .forEach(name->{try{owner(Long.parseLong(name));}catch(RuntimeException ignored){}});}
      for(var owner:owners.values())synchronized(owner){
        if(owner.job!=null||!Boolean.TRUE.equals(owner.disk.get("connected"))||!Boolean.TRUE.equals(owner.disk.get("autoSync")))continue;
        long last=owner.disk.get("lastAttempt") instanceof Number n?n.longValue():0L;
        if(System.currentTimeMillis()-last<integer(owner.disk.get("intervalHours"),1)*3_600_000L)continue;
        try{sync(owner.id,Map.of());}catch(RuntimeException ignored){}
      }
    }catch(IOException ignored){}
  }

  private Map<String,Object> normalizeDay(String date,Map<?,?> raw)throws WorkerFailure{
    if(!(raw.get("summary") instanceof Map<?,?> source)||!(raw.get("activities") instanceof List<?> activities)||activities.size()>2000)throw new WorkerFailure("PROTOCOL");
    var summary=new LinkedHashMap<String,Object>();for(String key:METRICS)summary.put(key,metric(source.get(key)));
    for(String key:List.of("sleepStartTime","sleepEndTime")){
      Object value=source.get(key);if(value!=null){if(!(value instanceof String time)||time.length()>50)throw new WorkerFailure("PROTOCOL");try{OffsetDateTime.parse(time);}catch(Exception error){throw new WorkerFailure("PROTOCOL");}}
      summary.put(key,value);
    }
    var workouts=new ArrayList<Map<String,Object>>();for(Object value:activities){
      if(!(value instanceof Map<?,?> activity))throw new WorkerFailure("PROTOCOL");var item=new LinkedHashMap<String,Object>();
      for(String key:List.of("id","name","type","startTime")){Object field=activity.get(key);String text=field==null?"":field.toString();if(text.length()>200||text.chars().anyMatch(c->c<32))throw new WorkerFailure("PROTOCOL");item.put(key,text);}
      for(String key:List.of("durationSeconds","distanceMeters","calories","averageHeartRate"))item.put(key,metric(activity.get(key)));workouts.add(item);
    }
    var warnings=new ArrayList<String>();if(raw.get("warnings") instanceof List<?> list)for(Object warning:list)if(WARNING_CODES.contains(String.valueOf(warning)))warnings.add(warning.toString());
    return Map.of("date",date,"summary",summary,"activities",workouts,"warnings",warnings);
  }
  private static Object metric(Object value)throws WorkerFailure{
    if(value==null)return null;if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<0)throw new WorkerFailure("PROTOCOL");return value;
  }
  private void save(Owner owner,Map<String,Object> next){
    try{directory(root);directory(owner.dir);atomic(owner.dir.resolve("state.json"),next);owner.disk=next;}
    catch(Exception error){throw problem(HttpStatus.INTERNAL_SERVER_ERROR,"佳明本地配置保存失败");}
  }
  private void atomic(Path target,Object value)throws IOException{
    directory(target.getParent());Path temporary=Files.createTempFile(target.getParent(),".garmin-",".tmp");
    try{mode(temporary,FILE_MODE);mapper.writeValue(temporary.toFile(),value);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    finally{Files.deleteIfExists(temporary);}
  }
  private static void copyCache(Path from,Path to)throws IOException{
    if(!Files.isDirectory(from))return;try(var files=Files.list(from)){for(Path file:files.filter(Files::isRegularFile).toList()){
      if(!file.getFileName().toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")||Files.exists(to.resolve(file.getFileName())))continue;
      Files.copy(file,to.resolve(file.getFileName()));mode(to.resolve(file.getFileName()),FILE_MODE);
    }}
  }
  private static Path session(Owner owner,String id){return owner.dir.resolve("sessions").resolve(uuid(id));}
  private static Path cache(Owner owner,String id){return owner.dir.resolve("data").resolve(uuid(id));}
  private static String uuid(String value){try{if(UUID.fromString(value).toString().equals(value))return value;}catch(Exception ignored){}throw problem(HttpStatus.INTERNAL_SERVER_ERROR,"佳明本地配置无效");}
  private static void directory(Path dir)throws IOException{Files.createDirectories(dir);mode(dir,DIR_MODE);}
  private static void mode(Path path,Set<PosixFilePermission> permissions)throws IOException{try{Files.setPosixFilePermissions(path,permissions);}catch(UnsupportedOperationException ignored){}}
  private static void secureTree(Path dir)throws IOException{try(var files=Files.walk(dir)){for(Path path:files.toList())mode(path,Files.isDirectory(path)?DIR_MODE:FILE_MODE);}}
  private static void delete(Path dir){try{if(Files.exists(dir))try(var files=Files.walk(dir)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}catch(IOException ignored){}}
  private static void cancel(Worker worker){if(worker!=null)try{worker.cancel();}catch(RuntimeException ignored){}}
  private String fingerprint(String email,String region){
    try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(config.get("REPORT_SESSION_SECRET","garmin-local-test").getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal((region+":"+email.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)));}
    catch(Exception error){throw problem(HttpStatus.INTERNAL_SERVER_ERROR,"佳明账户标识生成失败");}
  }
  private static LocalDate[] range(String start,String end,int days){
    try{
      LocalDate today=LocalDate.now(ReportService.BEIJING),last=end.isBlank()?today:date(end),first=start.isBlank()?last.minusDays(days-1L):date(start);
      if(first.isBefore(LocalDate.of(2000,1,1))||first.isAfter(last)||last.isAfter(today)||ChronoUnit.DAYS.between(first,last)>=90)throw new IllegalArgumentException();return new LocalDate[]{first,last};
    }catch(Exception error){throw problem(HttpStatus.BAD_REQUEST,"同步日期必须有效、不晚于今天且最多 90 天");}
  }
  private static LocalDate date(String date){if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();return LocalDate.parse(date);}
  private static String region(String value){if(value.isBlank())return "global";if(!Set.of("global","cn").contains(value))throw problem(HttpStatus.BAD_REQUEST,"请选择国际区或中国区");return value;}
  private static String field(Map<String,Object> input,String key,int max){if(!(input.get(key) instanceof String value)||value.length()>max)throw problem(HttpStatus.BAD_REQUEST,"佳明连接信息格式错误");return value;}
  private static String text(Map<?,?> map,String key){return Objects.toString(map.get(key),"");}
  private static int integer(Object value,int fallback){try{return Integer.parseInt(String.valueOf(value));}catch(Exception ignored){return fallback;}}
  private static ResponseStatusException problem(HttpStatus status,String message){return new ResponseStatusException(status,message);}
  private static String safeError(String code){return switch(code){
    case "AUTH"->"佳明登录失败，请检查邮箱、密码及地区";case "MFA"->"验证码错误或已过期，请重新连接";
    case "SESSION_EXPIRED"->"佳明登录会话已失效，请重新连接";case "NETWORK"->"佳明网络请求失败，旧数据已保留";
    case "RATE_LIMIT"->"佳明请求暂时受限，请稍后重试，旧数据已保留";case "DEPENDENCY"->"服务器佳明 Python 依赖尚未配置";
    case "TIMEOUT"->"佳明任务超时，请重试，旧数据已保留";case "PERMISSION"->"网站账户已停用或佳明权限已关闭";
    case "PROTOCOL"->"佳明同步结果不完整或格式异常，旧数据已保留";case "IO"->"佳明本地缓存写入失败，旧数据已保留";
    case "CANCELLED"->"佳明任务已取消，旧数据已保留";default->"佳明同步失败，请稍后重试，旧数据已保留";
  };}
  @PreDestroy public void close(){closed=true;for(var owner:owners.values())synchronized(owner){if(owner.job!=null){owner.job.cancelled=true;cancel(owner.job.worker);}}workers.shutdownNow();timeouts.shutdownNow();}

  static final class ProcessRunner implements WorkerRunner {
    private final RuntimeConfig config;private final ObjectMapper mapper;
    private long checkedAt;private boolean ready;
    ProcessRunner(RuntimeConfig config){this.config=config;this.mapper=config.objectMapper();}
    private Path script(){return Path.of(config.get("GARMIN_WORKER_SCRIPT","scripts/garmin_worker.py")).toAbsolutePath().normalize();}
    private String python(){
      String override=config.get("GARMIN_PYTHON","");if(!override.isBlank())return override;
      Path local=config.runtimeDir().resolve("garmin-python").resolve(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"Scripts/python.exe":"bin/python");
      return Files.isRegularFile(local)?local.toAbsolutePath().toString():"python3";
    }
    @Override public synchronized boolean available(){
      long now=System.currentTimeMillis();if(checkedAt!=0&&now-checkedAt<60_000)return ready;
      ready=false;Process process=null;
      try{
        if(Files.isRegularFile(script())){
          process=new ProcessBuilder(python(),"-u",script().toString(),"--check").redirectError(ProcessBuilder.Redirect.DISCARD).start();
          if(process.waitFor(2,TimeUnit.SECONDS)&&process.exitValue()==0){
            var result=mapper.readValue(process.getInputStream().readNBytes(16384),Map.class);
            ready="ready".equals(text(result,"type"))&&"0.3.17".equals(text(result,"version"));
          }
        }
      }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
      catch(Exception ignored){}
      finally{
        if(process!=null){process.destroyForcibly();try{process.waitFor(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
        checkedAt=System.currentTimeMillis();
      }
      return ready;
    }
    @Override public Worker start()throws Exception{
      ProcessBuilder builder=new ProcessBuilder(python(),"-u",script().toString());builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      builder.environment().put("PYTHONUTF8","1");builder.environment().put("PYTHONUNBUFFERED","1");
      Process process;try{process=builder.start();}catch(IOException error){throw new WorkerFailure("DEPENDENCY");}
      return new Worker(){
        final BufferedReader output=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
        final Writer input=new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8);
        @Override public synchronized void send(Map<String,Object> value)throws Exception{input.write(mapper.writeValueAsString(value));input.write('\n');input.flush();}
        @Override public Map<String,Object> read()throws Exception{
          var line=new StringBuilder();int c;while((c=output.read())!=-1){if(c=='\n')break;if(line.length()>=2_000_000)throw new WorkerFailure("PROTOCOL");if(c!='\r')line.append((char)c);}
          if(line.isEmpty()){if(c!=-1)throw new WorkerFailure("PROTOCOL");return null;}
          try{return mapper.readValue(line.toString(),Map.class);}catch(Exception error){throw new WorkerFailure("PROTOCOL");}
        }
        @Override public int awaitExit()throws InterruptedException{return process.waitFor();}
        @Override public void cancel(){process.destroyForcibly();try{process.getOutputStream().close();}catch(IOException ignored){}try{process.getInputStream().close();}catch(IOException ignored){}}
      };
    }
  }
}
