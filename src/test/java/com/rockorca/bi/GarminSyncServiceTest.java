package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class GarminSyncServiceTest {
  @TempDir Path dir;
  final ObjectMapper mapper=new ObjectMapper();
  final UserService users=mock(UserService.class);
  final FakeRunner runner=new FakeRunner();
  RuntimeConfig config;GarminSyncService service;
  final String date=LocalDate.now(ReportService.BEIJING).toString();
  @BeforeEach void setup(){
    config=new RuntimeConfig(mapper);ReflectionTestUtils.setField(config,"runtimeDir",dir);
    when(users.findById(anyLong())).thenAnswer(call->new UserRepository.UserAccount(call.getArgument(0),"owner","hash","user",true,1,null,null,null));
    when(users.canUseTool(any(),eq("garmin"))).thenReturn(true);
    service=new GarminSyncService(config,users,runner,2,30_000,30_000);
  }
  @AfterEach void close(){service.close();}
  @FunctionalInterface interface Input {void accept(Map<String,Object> request)throws Exception;}
  static class FakeWorker implements GarminSyncService.Worker {
    static final Map<String,Object> EOF=Map.of("eof",true);
    final BlockingQueue<Map<String,Object>> events=new LinkedBlockingQueue<>();
    final List<Map<String,Object>> sent=new CopyOnWriteArrayList<>();
    final CountDownLatch started=new CountDownLatch(1);Input input;int exit;
    void event(Map<String,Object> event){events.add(event);}
    void finish(){events.add(EOF);}
    @Override public void send(Map<String,Object> request)throws Exception{sent.add(new LinkedHashMap<>(request));started.countDown();if(input!=null)input.accept(request);}
    @Override public Map<String,Object> read()throws Exception{var next=events.take();return next==EOF?null:next;}
    @Override public int awaitExit(){return exit;}
    @Override public void cancel(){finish();}
  }
  static class FakeRunner implements GarminSyncService.WorkerRunner {
    final Queue<FakeWorker> planned=new ConcurrentLinkedQueue<>();final AtomicInteger starts=new AtomicInteger();boolean available=true;
    FakeWorker add(){var worker=new FakeWorker();planned.add(worker);return worker;}
    @Override public GarminSyncService.Worker start(){starts.incrementAndGet();var worker=planned.poll();if(worker==null)throw new IllegalStateException("No fixture planned");return worker;}
    @Override public boolean available(){return available;}
  }
  static Map<String,Object> credentials(String email){return Map.of("email",email,"password","secret-password-fixture","region","global");}
  void await(BooleanSupplier ready)throws Exception{
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!ready.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(ready.getAsBoolean());
  }
  void idle(long owner)throws Exception{await(()->service.status(owner).get("jobId").equals(""));}
  static void tokens(Map<String,Object> request)throws Exception{Files.writeString(Path.of(request.get("sessionDir").toString()).resolve("garmin_tokens.json"),"{}");}
  FakeWorker login(long owner,String email)throws Exception{
    var worker=runner.add();worker.input=request->{tokens(request);worker.event(Map.of("type","connected"));worker.finish();};
    service.connect(owner,credentials(email));idle(owner);assertEquals(true,service.status(owner).get("connected"));return worker;
  }
  static Map<String,Object> payload(int steps){return Map.of("summary",Map.of("steps",steps,"sleepSeconds",28800,"privateToken","must-not-cache"),"activities",List.of(),"warnings",List.of("hrv","heart_rate","body_battery","private-warning"));}
  FakeWorker syncDay(long owner,int steps)throws Exception{
    var worker=runner.add();worker.input=request->{worker.event(Map.of("type","day","date",date,"data",payload(steps)));
      worker.event(Map.of("type","done","startDate",date,"endDate",date,"dayCount",1));worker.finish();};
    service.sync(owner,Map.of("startDate",date,"endDate",date));idle(owner);assertEquals("",service.status(owner).get("error"));return worker;
  }
  Map<?,?> day(long owner){return (Map<?,?>)((List<?>)service.data(owner,date,date).get("days")).getFirst();}
  static Map<String,Object> record(Object value){return Map.of("state","available","value",value,"truncated",false,"collectedAt","2026-10-12T01:00:00Z");}
  void detailedSync(long owner,Map<String,Object> payload,Map<String,Object> input)throws Exception{
    var worker=runner.add();worker.input=request->{worker.event(Map.of("type","day","date",date,"data",payload));worker.event(Map.of("type","done","startDate",date,"endDate",date,"dayCount",1));worker.finish();};
    service.sync(owner,input);idle(owner);assertEquals("",service.status(owner).get("error"));
  }
  @Test void detailsArePrivateBoundedLazyAndRetainedByBasicSync()throws Exception{
    login(7,"alice@example.com");
    var payload=new LinkedHashMap<String,Object>(payload(5));payload.put("details",Map.of(
        "spo2",record(Map.of("newProviderMetric",99,"nested",Map.of("password","hidden","value",95))),
        "private_endpoint",record(Map.of("cookie","hidden"))));
    detailedSync(7,payload,Map.of("startDate",date,"endDate",date,"includeDetails",true));
    var full=(Map<?,?>)service.day(7,date).get("day");assertFalse(full.toString().contains("hidden"));assertFalse(full.toString().contains("private_endpoint"));
    assertTrue(full.toString().contains("newProviderMetric"));assertFalse(day(7).containsKey("details"));assertEquals(1,day(7).get("detailCount"));
    assertEquals(404,assertThrows(ResponseStatusException.class,()->service.day(8,date)).getStatusCode().value());
    assertThrows(ResponseStatusException.class,()->service.day(7,"../../state"));assertThrows(ResponseStatusException.class,()->service.day(7,""));
    syncDay(7,6);assertTrue(service.day(7,date).toString().contains("newProviderMetric"));
    String dataId=Objects.toString(((Map<?,?>)mapper.readValue(dir.resolve("garmin/7/state.json").toFile(),Map.class)).get("dataId"));
    Path cache=dir.resolve("garmin/7/data").resolve(dataId);assertTrue(Files.isRegularFile(cache.resolve(date+".summary.json")));
    Files.writeString(cache.resolve(date+".json"),"corrupt detail fixture");
    assertEquals(6,((Map<?,?>)day(7).get("summary")).get("steps"),"range queries use the lightweight summary index");
  }
  @Test void activityRefreshIsRestrictedToSavedOwnersActivitiesAndSurvivesLaterBasicSync()throws Exception{
    login(7,"alice@example.com");var workout=new LinkedHashMap<String,Object>(Map.of("id","123","name","Run","type","running","durationSeconds",100));
    workout.put("overview",record(Map.of("averagePower",200)));
    var payload=new LinkedHashMap<String,Object>(payload(10));payload.put("activities",List.of(workout));
    detailedSync(7,payload,Map.of("startDate",date,"endDate",date));
    assertThrows(ResponseStatusException.class,()->service.sync(7,Map.of("startDate",date,"endDate",date,"activityId","999")));
    workout.put("details",Map.of("splits",record(List.of(Map.of("distance",1000)))));
    detailedSync(7,payload,Map.of("startDate",date,"endDate",date,"activityId","123"));
    assertTrue(service.day(7,date).toString().contains("splits"));
    workout.remove("details");detailedSync(7,payload,Map.of("startDate",date,"endDate",date));
    assertTrue(service.day(7,date).toString().contains("splits"));assertFalse(service.data(7,date,date).toString().contains("averagePower"));
  }
  @Test void invalidDetailPayloadNeverOverwritesCache()throws Exception{
    login(7,"alice@example.com");syncDay(7,12);
    var bad=new LinkedHashMap<String,Object>(payload(99));bad.put("details",Map.of("sleep",record(Map.of("value","x".repeat(501)))));
    var worker=runner.add();worker.input=request->{worker.event(Map.of("type","day","date",date,"data",bad));worker.finish();};
    service.sync(7,Map.of("startDate",date,"endDate",date,"includeDetails",true));idle(7);
    assertFalse(service.status(7).get("error").toString().isBlank());assertEquals(12,((Map<?,?>)day(7).get("summary")).get("steps"));
  }

  @Test void readKeysAreHashedPersistentOwnerBoundAndRotatable()throws Exception{
    assertEquals(409,assertThrows(ResponseStatusException.class,()->service.createReadKey(7,Map.of("days",30))).getStatusCode().value());
    login(7,"alice@example.com");syncDay(7,13);
    assertThrows(ResponseStatusException.class,()->service.createReadKey(7,Map.of("days",365)));
    var issued=service.createReadKey(7,Map.of("days",30));String key=issued.get("apiKey").toString();
    assertTrue(key.matches("ghk_7_[A-Za-z0-9_-]{43}"));assertFalse(service.status(7).containsKey("apiKey"));
    String disk=Files.readString(dir.resolve("garmin/7/state.json"));assertFalse(disk.contains(key));assertTrue(disk.contains("readKeyHash"));
    assertEquals(13,((Map<?,?>)((Map<?,?>)((List<?>)service.withReadKey("Bearer "+key,id->service.data(id,date,date)).get("days")).getFirst()).get("summary")).get("steps"));
    service.close();service=new GarminSyncService(config,users,runner,2,30_000,30_000);
    assertEquals("website_cache",service.withReadKey("Bearer "+key,service::externalStatus).get("source"));
    for(String authorization:List.of("",key,"Bearer "+key.replace("ghk_7_","ghk_8_"),"Bearer "+key+" ","Bearer ghk_999_"+"a".repeat(43)))
      assertEquals(401,assertThrows(ResponseStatusException.class,()->service.withReadKey(authorization,service::externalStatus)).getStatusCode().value());
    String replacement=service.createReadKey(7,Map.of("days",7)).get("apiKey").toString();
    assertThrows(ResponseStatusException.class,()->service.withReadKey("Bearer "+key,service::externalStatus));
    assertNotNull(service.withReadKey("Bearer "+replacement,service::externalStatus));service.revokeReadKey(7);
    assertThrows(ResponseStatusException.class,()->service.withReadKey("Bearer "+replacement,service::externalStatus));
    assertEquals(false,((Map<?,?>)service.status(7).get("readKey")).get("enabled"));
  }
  @Test void readKeyExpiryAndPermissionRevocationRejectReads()throws Exception{
    login(7,"alice@example.com");String key=service.createReadKey(7,Map.of("days",30)).get("apiKey").toString();
    when(users.canUseTool(any(),eq("garmin"))).thenReturn(false);
    assertEquals(403,assertThrows(ResponseStatusException.class,()->service.withReadKey("Bearer "+key,service::externalStatus)).getStatusCode().value());
    when(users.canUseTool(any(),eq("garmin"))).thenReturn(true);service.close();
    Path path=dir.resolve("garmin/7/state.json");var saved=mapper.readValue(path.toFile(),Map.class);saved.put("readKeyExpiresAt",System.currentTimeMillis()-1);mapper.writeValue(path.toFile(),saved);
    service=new GarminSyncService(config,users,runner,2,30_000,30_000);
    assertEquals(401,assertThrows(ResponseStatusException.class,()->service.withReadKey("Bearer "+key,service::externalStatus)).getStatusCode().value());
  }
  @Test void successfulAccountSwitchRevokesReadKeyButSameAccountReconnectKeepsIt()throws Exception{
    login(7,"alice@example.com");String key=service.createReadKey(7,Map.of("days",90)).get("apiKey").toString();
    login(7,"ALICE@example.com");assertNotNull(service.withReadKey("Bearer "+key,service::externalStatus));
    login(7,"bob@example.com");assertEquals(401,assertThrows(ResponseStatusException.class,()->service.withReadKey("Bearer "+key,service::externalStatus)).getStatusCode().value());
    assertEquals(false,((Map<?,?>)service.status(7).get("readKey")).get("enabled"));
  }
  @Test void readKeyQuotaDoesNotStartWorkersOrExposeSecrets()throws Exception{
    login(7,"alice@example.com");String key=service.createReadKey(7,Map.of("days",30)).get("apiKey").toString();int starts=runner.starts.get();
    for(int i=0;i<60;i++)assertNotNull(service.withReadKey("Bearer "+key,service::externalStatus));
    assertEquals(429,assertThrows(ResponseStatusException.class,()->service.withReadKey("Bearer "+key,service::externalStatus)).getStatusCode().value());
    assertEquals(starts,runner.starts.get());
  }
  @Test void revocationWaitsForAnAuthorizedCacheReadToFinish()throws Exception{
    login(7,"alice@example.com");String key=service.createReadKey(7,Map.of("days",30)).get("apiKey").toString();
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var revoking=new CountDownLatch(1);
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
      var reading=executor.submit(()->service.withReadKey("Bearer "+key,id->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException error){throw new RuntimeException(error);}return service.externalStatus(id);}));
      assertTrue(entered.await(3,TimeUnit.SECONDS));var revoked=executor.submit(()->{revoking.countDown();return service.revokeReadKey(7);});
      assertTrue(revoking.await(3,TimeUnit.SECONDS));assertFalse(revoked.isDone());release.countDown();assertNotNull(reading.get(3,TimeUnit.SECONDS));revoked.get(3,TimeUnit.SECONDS);
      assertEquals(401,assertThrows(ResponseStatusException.class,()->service.withReadKey("Bearer "+key,service::externalStatus)).getStatusCode().value());
    }finally{release.countDown();}
  }

  @Test void passwordOnlyTravelsOverStdinAndMfaRetriesStayOnTheSameWorker()throws Exception{
    var worker=runner.add();var attempts=new AtomicInteger();
    worker.input=request->{if(request.get("action").equals("connect"))worker.event(Map.of("type","mfa","method","email"));
      else if(attempts.incrementAndGet()==1)worker.event(Map.of("type","mfa","invalidCode",true));
      else{tokens(worker.sent.getFirst());worker.event(Map.of("type","connected"));worker.finish();}};
    var status=service.connect(7,credentials("alice@example.com"));String jobId=status.get("jobId").toString();
    await(()->service.status(7).get("state").equals("mfa_required"));
    assertThrows(ResponseStatusException.class,()->service.mfa(8,Map.of("code","123456","jobId",jobId)));
    assertThrows(ResponseStatusException.class,()->service.mfa(7,Map.of("code","123456","jobId","stale")));
    service.mfa(7,Map.of("code","123456","jobId",jobId));
    await(()->((Map<?,?>)service.status(7).get("mfa")).get("invalidCode").equals(true));
    service.mfa(7,Map.of("code","654321","jobId",jobId));idle(7);
    assertEquals(1,runner.starts.get());assertEquals(3,worker.sent.size());assertEquals("secret-password-fixture",worker.sent.getFirst().get("password"));
    assertFalse(service.status(7).toString().contains("alice@example.com"));assertFalse(service.status(7).toString().contains("secret-password"));
    try(var files=Files.walk(dir)){for(Path file:files.filter(Files::isRegularFile).toList()){
      String saved=Files.readString(file);assertFalse(saved.contains("secret-password"));assertFalse(saved.contains("alice@example.com"));
    }}
  }
  @Test void completeSyncPublishesOnlyWhitelistedFieldsAndPreservesZeroAndMissingValues()throws Exception{
    var login=login(7,"alice@example.com");syncDay(7,0);
    var result=day(7);var summary=(Map<?,?>)result.get("summary");assertEquals(0,summary.get("steps"));assertNull(summary.get("hrvMs"));
    assertFalse(result.toString().contains("must-not-cache"));assertEquals(List.of("hrv","heart_rate","body_battery"),result.get("warnings"));
    assertEquals(List.of(),service.data(7,date,date).get("missingDates"));assertEquals(List.of(),service.data(8,date,date).get("days"));
    Path tokens=Path.of(login.sent.getFirst().get("sessionDir").toString()).resolve("garmin_tokens.json");
    if(Files.getFileStore(tokens).supportsFileAttributeView("posix")){
      assertEquals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE),Files.getPosixFilePermissions(tokens));
      assertEquals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE),Files.getPosixFilePermissions(tokens.getParent()));
    }
  }
  @Test void partialErrorWrongDoneAndNonzeroExitNeverOverwriteACompleteCache()throws Exception{
    login(7,"alice@example.com");syncDay(7,12);String success=service.status(7).get("lastSuccess").toString();
    for(String failure:List.of("error","wrong-count","nonzero")){
      var worker=runner.add();worker.input=request->{worker.event(Map.of("type","day","date",date,"data",payload(999)));
        if(failure.equals("error"))worker.event(Map.of("type","error","code","NETWORK","message","secret-password-fixture"));
        else{worker.exit=failure.equals("nonzero")?1:0;worker.event(Map.of("type","done","startDate",date,"endDate",date,"dayCount",failure.equals("wrong-count")?2:1));}
        worker.finish();};
      service.sync(7,Map.of("startDate",date,"endDate",date));idle(7);
      assertEquals(12,((Map<?,?>)day(7).get("summary")).get("steps"));assertEquals(success,service.status(7).get("lastSuccess"));
      assertFalse(service.status(7).get("error").toString().isBlank());assertFalse(service.status(7).toString().contains("secret-password"));
    }
    try(var dirs=Files.list(dir.resolve("garmin/7/data"))){assertEquals(1,dirs.count());}
  }
  @Test void sameAccountReconnectKeepsCacheFailedSwitchKeepsBothAndSuccessfulSwitchIsolatesData()throws Exception{
    login(7,"alice@example.com");syncDay(7,42);login(7,"ALICE@example.com");assertEquals(1,((List<?>)service.data(7,date,date).get("days")).size());
    var failure=runner.add();failure.input=request->{failure.event(Map.of("type","error","code","AUTH"));failure.finish();};
    service.connect(7,credentials("bob@example.com"));idle(7);assertEquals(true,service.status(7).get("connected"));assertEquals(42,((Map<?,?>)day(7).get("summary")).get("steps"));
    login(7,"bob@example.com");assertEquals(List.of(),service.data(7,date,date).get("days"));assertEquals(true,service.status(7).get("accountChanged"));
    assertEquals("",service.status(7).get("lastSuccess"));syncDay(7,88);assertEquals(false,service.status(7).get("accountChanged"));
    try(var dirs=Files.list(dir.resolve("garmin/7/sessions"))){assertEquals(1,dirs.count());}
  }
  @Test void disconnectCancelsAnActiveJobAndLateResultsCannotRestoreTokensOrReplaceCache()throws Exception{
    login(7,"alice@example.com");syncDay(7,7);var worker=runner.add();
    service.sync(7,Map.of("startDate",date,"endDate",date));assertTrue(worker.started.await(3,TimeUnit.SECONDS));
    service.disconnect(7);worker.event(Map.of("type","day","date",date,"data",payload(999)));worker.finish();idle(7);
    assertEquals(false,service.status(7).get("connected"));assertEquals(7,((Map<?,?>)day(7).get("summary")).get("steps"));
    assertFalse(Files.exists(dir.resolve("garmin/7/sessions")));assertEquals("",service.status(7).get("error"));
  }
  @Test void ownerMutexAndGlobalLimitRejectExtraJobsAndReleaseSlotsAfterCancellation()throws Exception{
    var first=runner.add();var second=runner.add();service.connect(7,credentials("a@example.com"));
    assertEquals(409,assertThrows(ResponseStatusException.class,()->service.connect(7,credentials("a@example.com"))).getStatusCode().value());
    service.connect(8,credentials("b@example.com"));assertEquals(429,assertThrows(ResponseStatusException.class,()->service.connect(9,credentials("c@example.com"))).getStatusCode().value());
    assertTrue(first.started.await(3,TimeUnit.SECONDS));assertTrue(second.started.await(3,TimeUnit.SECONDS));service.disconnect(7);idle(7);
    login(9,"c@example.com");service.disconnect(8);idle(8);assertEquals(3,runner.starts.get());
  }
  @Test void timeoutAndPermissionRevocationCannotPublishStagedResults()throws Exception{
    login(7,"alice@example.com");syncDay(7,5);var blocked=runner.add();service.sync(7,Map.of("startDate",date,"endDate",date));assertTrue(blocked.started.await(3,TimeUnit.SECONDS));
    when(users.canUseTool(any(),eq("garmin"))).thenReturn(false);blocked.event(Map.of("type","day","date",date,"data",payload(999)));blocked.finish();idle(7);
    assertEquals(5,((Map<?,?>)day(7).get("summary")).get("steps"));assertTrue(service.status(7).get("error").toString().contains("权限"));
    when(users.canUseTool(any(),eq("garmin"))).thenReturn(true);service.close();service=new GarminSyncService(config,users,runner,2,50,50);
    runner.add();service.sync(7,Map.of("startDate",date,"endDate",date));idle(7);assertTrue(service.status(7).get("error").toString().contains("超时"));
    assertEquals(5,((Map<?,?>)day(7).get("summary")).get("steps"));
  }
  @Test void automaticSyncDefaultsOffAndRestartsFromPersistedHourlySchedule()throws Exception{
    login(7,"alice@example.com");assertEquals(false,((Map<?,?>)service.status(7).get("settings")).get("autoSync"));service.dispatch();assertEquals(1,runner.starts.get());
    service.settings(7,Map.of("autoSync",true,"intervalHours",1,"days",1));service.close();
    Path state=dir.resolve("garmin/7/state.json");var saved=mapper.readValue(state.toFile(),Map.class);saved.put("lastAttempt",0L);mapper.writeValue(state.toFile(),saved);
    service=new GarminSyncService(config,users,runner,2,30_000,30_000);var worker=runner.add();
    worker.input=request->{worker.event(Map.of("type","day","date",date,"data",payload(11)));worker.event(Map.of("type","done","startDate",date,"endDate",date,"dayCount",1));worker.finish();};
    service.dispatch();idle(7);assertEquals(2,runner.starts.get());assertEquals(11,((Map<?,?>)day(7).get("summary")).get("steps"));
    service.dispatch();assertEquals(2,runner.starts.get());assertEquals(1,((Map<?,?>)service.status(7).get("settings")).get("days"));
  }
  @Test void invalidInputsAndDependencyFailureNeverStartAWorkerOrExposeRawErrors(){
    assertThrows(ResponseStatusException.class,()->service.connect(7,Map.of("email","bad","password","secret")));
    assertThrows(ResponseStatusException.class,()->service.data(7,"1999-12-31",date));
    assertThrows(ResponseStatusException.class,()->service.data(7,LocalDate.now(ReportService.BEIJING).minusDays(90).toString(),date));
    assertThrows(ResponseStatusException.class,()->service.settings(7,Map.of("autoSync",true,"intervalHours",2,"days",7)));
    runner.available=false;assertEquals(false,service.status(7).get("available"));
    assertEquals(503,assertThrows(ResponseStatusException.class,()->service.connect(7,credentials("a@example.com"))).getStatusCode().value());assertEquals(0,runner.starts.get());
  }
  @Test void connectedEventWithoutTheRequiredNonemptyTokenFileCannotPublishALogin()throws Exception{
    var worker=runner.add();worker.input=request->{Files.writeString(Path.of(request.get("sessionDir").toString()).resolve("unrelated.txt"),"not tokens");worker.event(Map.of("type","connected"));worker.finish();};
    service.connect(7,credentials("a@example.com"));idle(7);assertEquals(false,service.status(7).get("connected"));assertTrue(service.status(7).get("error").toString().contains("格式"));
  }
  @SuppressWarnings("unchecked")
  void override(String key,String value){((Map<String,String>)ReflectionTestUtils.getField(config,"values")).put(key,value);}
  Path localPython(){return Path.of(".runtime/garmin-python").resolve(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"Scripts/python.exe":"bin/python").toAbsolutePath();}
  @Test void actualLocalWorkerDependencyProbeIsCachedAndNeverNeedsLoginCredentials(){
    Assumptions.assumeTrue(Files.isRegularFile(localPython()),"Optional local Garmin Python environment");
    override("GARMIN_PYTHON",localPython().toString());override("GARMIN_WORKER_SCRIPT",Path.of("scripts/garmin_worker.py").toAbsolutePath().toString());
    var processRunner=new GarminSyncService.ProcessRunner(config);assertTrue(processRunner.available());
    override("GARMIN_WORKER_SCRIPT",dir.resolve("missing-worker.py").toString());assertTrue(processRunner.available());
    assertFalse(new GarminSyncService.ProcessRunner(config).available());
  }
  @Test void dependencyProbeTimesOutLocallyAndCannotMakeTheBiApplicationHang()throws Exception{
    Assumptions.assumeTrue(Files.isRegularFile(localPython()),"Optional local Garmin Python environment");
    Path script=dir.resolve("slow-check.py");Files.writeString(script,"import time\ntime.sleep(20)\n");
    override("GARMIN_PYTHON",localPython().toString());override("GARMIN_WORKER_SCRIPT",script.toString());
    long start=System.nanoTime();assertFalse(new GarminSyncService.ProcessRunner(config).available());
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<5000,"Probe must stop before the worker's 20-second delay");
  }
}
