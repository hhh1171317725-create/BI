package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class BidServerSyncServiceTest {
  @TempDir Path dir;
  private final MemoryStore store=new MemoryStore();
  private final BidMonitorApiController upstream=mock(BidMonitorApiController.class);
  private final GdtBidMonitorClient gdt=mock(GdtBidMonitorClient.class);
  private final BidSnapshotController snapshots=mock(BidSnapshotController.class);
  private final BidProviderRawStore rawStore=mock(BidProviderRawStore.class);
  private final UserService users=mock(UserService.class);
  private BidCredentialCipher cipher;
  private BidServerSyncService service;

  static class MemoryStore extends BidServerSyncStore {
    final Map<Long,Map<String,Object>> states=new HashMap<>();
    final Connection connection=mock(Connection.class);
    MemoryStore(){super(null,null);}
    @Override synchronized Map<String,Object> get(long owner){return new LinkedHashMap<>(states.getOrDefault(owner,Map.of()));}
    @Override synchronized Map<String,Object> update(long owner,Update action)throws Exception{
      var state=get(owner);action.apply(connection,state);states.put(owner,state);return get(owner);
    }
  }
  @BeforeEach void setup()throws Exception{
    var config=mock(RuntimeConfig.class);when(config.runtimeDir()).thenReturn(dir);
    cipher=new BidCredentialCipher(config);
    when(users.findById(anyLong())).thenAnswer(call->new UserRepository.UserAccount(call.getArgument(0),"operator","hash","admin",true,1,null,null,null));
    when(users.canUseTool(any(),eq("bidMonitor"))).thenReturn(true);
    when(gdt.page(anyMap())).thenReturn(Map.of("total",0,"rows",List.of()));
    service=new BidServerSyncService(store,cipher,upstream,gdt,snapshots,rawStore,users);
  }
  @AfterEach void close(){service.close();}
  private Map<String,Object> input(){return new LinkedHashMap<>(Map.of("cookie","userId=123; chuangliang_session=private-test-cookie", "clientUser","123","mainUserId","456","minutes",10,"createdDays",7));}
  private List<Map<String,Object>> rows(int offset,int size){
    var rows=new ArrayList<Map<String,Object>>();
    for(int i=0;i<size;i++){
      var row=new LinkedHashMap<String,Object>(Map.of("promotion_id","768107547558"+(offset+i),"promotion_name","plan","media_account_id","123",
          "advertiser_nick","account","user_name","optimizer-A","stat_cost",1000-offset-i,"convert_cnt",2,"active_register",20,"cpa_bid",5,"cookie","must-drop"));
      row.put("advertiser_id","1866402186668232");row.put("promotion_create_time","2026-09-05 08:00:00");
      row.put("app_type_text","小程序");row.put("deep_bid_type_text","深度转化");row.put("deep_cpabid",88.5);
      row.put("deep_external_action_text","深度付费");row.put("external_action_text","注册");row.put("status_text","投放中");
      row.put("provider_data",new LinkedHashMap<>(row));rows.add(row);
    }
    return rows;
  }
  @Test void encryptsAtRestAndReturnsOnlySafeStatus()throws Exception{
    var result=service.start(7,input());var stored=store.get(7);
    assertTrue((Boolean)result.get("configured"));assertFalse(result.containsKey("credential"));assertFalse(result.containsKey("token"));
    assertFalse(stored.toString().contains("private-test-cookie"));assertEquals(input().get("cookie"),cipher.decrypt(7,stored.get("credential").toString()));
    assertFalse((Boolean)service.status(8).get("configured"));
    var blank=input();blank.put("cookie","");assertDoesNotThrow(()->service.start(7,blank));
    assertThrows(IllegalArgumentException.class,()->service.start(8,blank));
    blank.put("mainUserId","999");assertThrows(IllegalArgumentException.class,()->service.start(7,blank));
  }
  @Test void keySurvivesRestartAndRejectsTamperingAndDifferentOwner()throws Exception{
    String first=cipher.encrypt(7,"secret"),second=cipher.encrypt(7,"secret");assertNotEquals(first,second);
    var config=mock(RuntimeConfig.class);when(config.runtimeDir()).thenReturn(dir);
    assertEquals("secret",new BidCredentialCipher(config).decrypt(7,first));
    assertThrows(Exception.class,()->cipher.decrypt(8,first));
    byte[] bytes=Base64.getDecoder().decode(first);bytes[15]^=1;
    assertThrows(Exception.class,()->cipher.decrypt(7,Base64.getEncoder().encodeToString(bytes)));
    Files.delete(dir.resolve("bid-monitor.key"));
    assertThrows(Exception.class,()->cipher.decrypt(7,first));assertFalse(Files.exists(dir.resolve("bid-monitor.key")));
  }
  @Test void manualQuerySavesOnceAndReusesCredentialsWithoutPostponingSchedule()throws Exception{
    long before=System.currentTimeMillis();
    var prepared=service.prepareQuery(7,input());var saved=store.get(7);
    assertEquals(10,prepared.get("minutes"));assertEquals(true,prepared.get("enabled"));
    assertEquals("ready",prepared.get("state"));assertFalse(prepared.toString().contains("private-test-cookie"));
    long due=((Number)saved.get("dueAt")).longValue();assertTrue(due>=before+600000);
    assertTrue(due<=System.currentTimeMillis()+600000);
    service.run(7);verifyNoInteractions(upstream);
    var reused=service.prepareQuery(7,Map.of("cookie","","clientUser","","mainUserId",""));
    assertEquals(prepared.get("queryRevision"),reused.get("queryRevision"));
    assertEquals(due,store.get(7).get("dueAt"));assertEquals(saved.get("token"),store.get(7).get("token"));
    assertThrows(IllegalArgumentException.class,()->service.prepareQuery(8,Map.of()));
  }
  @Test void queryUsesOwnedStoredIdentityAndRejectsStaleOrForeignRevisions()throws Exception{
    var prepared=service.prepareQuery(7,input());
    var query=new LinkedHashMap<String,Object>(Map.of("queryRevision",prepared.get("queryRevision"),"page",1,
        "cookie","forged","clientUser","999","mainUserId","999","startDate","2026-09-03"));
    when(upstream.page(anyMap())).thenAnswer(call->{
      Map<String,Object> request=call.getArgument(0);
      assertEquals(input().get("cookie"),request.get("cookie"));assertEquals("123",request.get("clientUser"));
      assertEquals("456",request.get("mainUserId"));assertEquals(1,request.get("page"));
      assertFalse(request.containsKey("queryRevision"));return Map.of("total",1,"rows",List.of());
    });
    assertEquals(1,service.queryPage(7,query).get("total"));
    assertThrows(IllegalArgumentException.class,()->service.queryPage(8,query));
    assertThrows(IllegalArgumentException.class,()->service.queryPage(7,Map.of("queryRevision","stale")));
    verify(upstream,times(1)).page(anyMap());
    service.prepareQuery(7,input());assertThrows(IllegalArgumentException.class,()->service.queryPage(7,query));
    service.command(7,"forget");assertFalse(store.get(7).containsKey("credentialRevision"));
  }
  @Test void queryRoutesGdtPagesThroughTheSecondProvider()throws Exception{
    var prepared=service.prepareQuery(7,input());
    when(gdt.page(anyMap())).thenReturn(Map.of("total",2,"rows",List.of()));
    var result=service.queryPage(7,Map.of("expectedUserId","7","queryRevision",prepared.get("queryRevision"),
        "platform","gdt","page",1,"startDate","2026-09-09","endDate","2026-09-09"));
    assertEquals(2,result.get("total"));verify(gdt).page(argThat(request->request.get("page").equals(1)));
    verifyNoInteractions(upstream);
  }
  @Test void queryRejectsAccountChangedDuringNetworkRequest()throws Exception{
    var prepared=service.prepareQuery(7,input());
    when(upstream.page(anyMap())).thenAnswer(call->{service.prepareQuery(7,input());return Map.of("rows",List.of());});
    assertThrows(IllegalArgumentException.class,()->service.queryPage(7,Map.of("queryRevision",prepared.get("queryRevision"))));
  }
  @Test void currentQueryPersistsOptimizerForReloadAndRobot()throws Exception{
    var prepared=service.prepareQuery(7,input());
    var mapper=new tools.jackson.databind.ObjectMapper();var persisted=new java.util.concurrent.atomic.AtomicReference<String>();
    doAnswer(call->{assertEquals(7L,(long)call.getArgument(1));persisted.set(mapper.writeValueAsString(call.getArgument(2)));return null;})
        .when(snapshots).write(any(),eq(7L),anyMap());
    when(snapshots.readOwned(7)).thenAnswer(call->mapper.readValue(persisted.get(),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}));
    when(upstream.page(anyMap())).thenReturn(Map.of("total",1,"rows",rows(0,1)));
    var result=service.querySnapshot(7,Map.of("queryRevision",prepared.get("queryRevision")));
    assertEquals("7",result.get("userId"));assertEquals(mapper.writeValueAsString(result.get("snapshot")),mapper.writeValueAsString(snapshots.readOwned(7)));
    assertTrue(persisted.get().contains("optimizer-A"));assertFalse(persisted.get().contains("must-drop"));
    verify(rawStore).replace(eq(store.connection),eq(7L),anyString(),argThat(list->list.toString().contains("must-drop")));
    assertEquals(((Map<?,?>)result.get("snapshot")).get("updatedAt"),service.status(7).get("lastSuccess"));
    assertTrue(((Number)store.get(7).get("dueAt")).longValue()>System.currentTimeMillis()+590000);
    var pricing=service.savePricing(7,Map.of("revision","","rules",List.of(Map.of("name","task","keyword","account","price","10"))));
    var robot=mock(DingtalkRobotClient.class);
    var ding=new BidDingtalkService(store,cipher,snapshots,service,robot,mapper);
    try{
      ding.save(7,Map.of("revision","","pricingRevision",pricing.get("revision"),"tasks",List.of("task"),"time","18:00",
          "enabled",true,"webhook","https://oapi.dingtalk.com/robot/send?access_token=test-token-only","secret","","keyword","TOP5"));
      var preview=ding.preview(7);assertEquals("",preview.get("warning"));
      String text=((Map<?,?>)((List<?>)preview.get("messages")).getFirst()).get("text").toString();
      assertTrue(text.contains("optimizer-A"));assertTrue(text.contains("1866402186668232"));ding.send(7,false);
      verify(robot).send(anyString(),anyString(),eq("TOP5"),eq(text));
    }finally{ding.close();}
  }
  @Test void manualSnapshotNeverCommitsPartialOrRevokedResults()throws Exception{
    var prepared=service.prepareQuery(7,input());var query=Map.<String,Object>of("queryRevision",prepared.get("queryRevision"));
    assertThrows(IllegalArgumentException.class,()->service.querySnapshot(8,query));
    when(upstream.page(anyMap())).thenReturn(Map.of("total",400,"rows",rows(0,1)));
    assertThrows(IllegalArgumentException.class,()->service.querySnapshot(7,query));
    when(upstream.page(anyMap())).thenAnswer(call->{service.command(7,"stop");return Map.of("total",1,"rows",rows(0,1));});
    assertThrows(IllegalArgumentException.class,()->service.querySnapshot(7,query));
    verify(snapshots,never()).write(any(),anyLong(),anyMap());
  }
  @Test void transientFailureKeepsCredentialsAndRetriesInTenMinutes()throws Exception{
    service.start(7,input());
    when(upstream.page(anyMap())).thenThrow(new java.io.IOException("private-test-cookie"));
    service.run(7);var status=service.status(7);
    assertEquals("retrying",status.get("state"));assertEquals(true,status.get("enabled"));
    assertEquals(true,status.get("configured"));assertFalse(status.toString().contains("private-test-cookie"));
    assertTrue(((Number)status.get("dueAt")).longValue()>System.currentTimeMillis()+590000);
    verify(snapshots,never()).write(any(),anyLong(),anyMap());
    service.run(7);verify(upstream,times(1)).page(anyMap());
  }
  @Test void currentQueryRejectsNewerSnapshotAndChangedCredentials()throws Exception{
    var prepared=service.prepareQuery(7,input());var query=Map.<String,Object>of("queryRevision",prepared.get("queryRevision"));
    when(upstream.page(anyMap())).thenAnswer(call->{store.update(7,(connection,state)->state.put("lastSuccess","newer"));return Map.of("total",1,"rows",rows(0,1));});
    assertThrows(IllegalArgumentException.class,()->service.querySnapshot(7,query));
    when(upstream.page(anyMap())).thenAnswer(call->{service.prepareQuery(7,input());return Map.of("total",1,"rows",rows(0,1));});
    assertThrows(IllegalArgumentException.class,()->service.querySnapshot(7,query));
    verify(snapshots,never()).write(any(),anyLong(),anyMap());
  }
  @Test void readsAllFourPagesAndKeepsIdsAndMetrics()throws Exception{
    var pages=Collections.synchronizedList(new ArrayList<Integer>());
    when(upstream.page(anyMap())).thenAnswer(call->{Map<String,Object> request=call.getArgument(0);int page=(Integer)request.get("page");pages.add(page);if(page>1)assertEquals(350L,request.get("total"));return Map.of("total",350,"rows",rows((page-1)*100,page==4?50:100));});
    var snapshot=service.collect(input(),input().get("cookie").toString());
    assertEquals(List.of(1,2,3,4),pages.stream().sorted().toList());assertEquals(350,((List<?>)snapshot.get("rows")).size());
    assertEquals("created_window_all",snapshot.get("selection"));
    var row=(Map<?,?>)((List<?>)snapshot.get("rows")).getFirst();assertEquals("account",row.get("media_account_name"));
    assertEquals("2026-09-05 08:00:00",row.get("promotion_create_time"));
    assertEquals("7681075475580",row.get("promotion_id"));assertTrue(snapshot.toString().contains("must-drop"));
    assertEquals("optimizer-A",row.get("user_name"));assertEquals("123",row.get("media_account_id"));
    assertEquals("1866402186668232",row.get("advertiser_id"));
    assertEquals("小程序",row.get("app_type_text"));assertEquals("深度转化",row.get("deep_bid_type_text"));
    assertEquals(88.5,row.get("deep_cpabid"));assertEquals("深度付费",row.get("deep_external_action_text"));
    assertEquals("注册",row.get("external_action_text"));assertEquals("投放中",row.get("status_text"));
    assertEquals("字节",row.get("platform_text"));assertEquals("byte",row.get("source_platform"));
  }
  @Test void mergesByteAndGdtRowsWithoutCrossPlatformIdCollisions()throws Exception{
    var byteRows=rows(0,1);var gdtRows=rows(0,1);
    gdtRows.getFirst().put("source_platform","gdt");gdtRows.getFirst().put("platform_text","广点通");
    when(upstream.page(anyMap())).thenReturn(Map.of("total",1,"rows",byteRows));
    when(gdt.page(anyMap())).thenReturn(Map.of("total",1,"rows",gdtRows));
    var snapshot=service.collect(input(),input().get("cookie").toString());
    assertEquals(2,((List<?>)snapshot.get("rows")).size());assertEquals(2L,snapshot.get("upstreamTotal"));
    assertEquals(2L,snapshot.get("sourceTotal"));assertEquals(0L,snapshot.get("duplicateRows"));
  }
  @Test void creationWindowIncludesTodayAndThreePriorDates(){
    assertEquals(java.time.LocalDate.parse("2026-09-01"),BidServerSyncService.creationStart(java.time.LocalDate.parse("2026-09-04")));
    assertEquals(java.time.LocalDate.parse("2026-08-29"),BidServerSyncService.creationStart(java.time.LocalDate.parse("2026-09-01")));
    assertEquals(java.time.LocalDate.parse("2025-12-29"),BidServerSyncService.creationStart(java.time.LocalDate.parse("2026-01-01")));
  }
  @Test void readsEveryPageAndProgressTargetsTheFullTotal()throws Exception{
    var pages=Collections.synchronizedList(new ArrayList<Integer>());var progress=new ArrayList<Long>();
    when(upstream.page(anyMap())).thenAnswer(call->{
      int p=(Integer)((Map<?,?>)call.getArgument(0)).get("page");pages.add(p);
      return Map.of("total",450,"rows",rows((p-1)*100,p==5?50:100));
    });
    var snapshot=service.collect(input(),"cookie",(done,total)->progress.add(total));
    assertEquals(List.of(1,2,3,4,5),pages.stream().sorted().toList());assertEquals(450,((List<?>)snapshot.get("rows")).size());
    assertEquals(450L,snapshot.get("upstreamTotal"));assertEquals("created_window_all",snapshot.get("selection"));
    assertEquals(450L,progress.getLast());
  }
  @Test void fetchesPagesAfterTheFirstOnConcurrentVirtualThreads()throws Exception{
    var threads=java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
    when(upstream.page(anyMap())).thenAnswer(call->{
      int page=(Integer)((Map<?,?>)call.getArgument(0)).get("page");if(page>1)threads.add(Thread.currentThread().threadId());
      return Map.of("total",500,"rows",rows((page-1)*100,100));
    });
    assertEquals(500,((List<?>)service.collect(input(),"cookie").get("rows")).size());assertTrue(threads.size()>1);
  }
  @Test void refusesOversizedTotalsWithoutSavingAPartialSnapshot()throws Exception{
    when(upstream.page(anyMap())).thenReturn(Map.of("total",100001,"rows",rows(0,100)));
    assertThrows(IllegalArgumentException.class,()->service.collect(input(),"cookie"));
  }
  @Test void removesProviderDuplicateRowsAndStillFinishesEveryPage()throws Exception{
    when(upstream.page(anyMap())).thenAnswer(call->{
      int page=(Integer)((Map<?,?>)call.getArgument(0)).get("page");
      return Map.of("total",200,"rows",page==1?rows(0,100):rows(99,100));
    });
    var snapshot=service.collect(input(),"cookie");
    assertEquals(199,((List<?>)snapshot.get("rows")).size());
    assertEquals(199L,snapshot.get("upstreamTotal"));
    assertEquals(200L,snapshot.get("sourceTotal"));
    assertEquals(1L,snapshot.get("duplicateRows"));
    verify(upstream,times(2)).page(anyMap());
  }
  @Test void pricingIsOwnerScopedAndDoesNotChangeRunningJobToken()throws Exception{
    service.start(7,input());String token=store.get(7).get("token").toString();
    var rules=List.of(Map.of("name","taskA","keyword","account-A","price","21.5"));
    var result=service.savePricing(7,Map.of("revision","","rules",rules));
    assertEquals(rules,result.get("rules"));assertEquals(List.of(),service.pricing(8).get("rules"));
    assertEquals(token,store.get(7).get("token"));assertFalse(result.containsKey("credential"));
    assertThrows(ResponseStatusException.class,()->service.savePricing(7,Map.of("revision","","rules",List.of())));
    assertThrows(IllegalArgumentException.class,()->BidServerSyncService.validateRules(List.of(rules.getFirst(),rules.getFirst())));
    assertThrows(IllegalArgumentException.class,()->BidServerSyncService.validateRules(List.of(Map.of("name","t","keyword","","price",1))));
    assertThrows(IllegalArgumentException.class,()->BidServerSyncService.validateRules(List.of(Map.of("name","t","keyword","x","price",0))));
  }
  @Test void renewsLeaseDuringLongCollectionAndCanStopBeforeNextPage()throws Exception{
    service.start(7,input());var pages=Collections.synchronizedList(new ArrayList<Integer>());
    when(upstream.page(anyMap())).thenAnswer(call->{
      int p=(Integer)((Map<?,?>)call.getArgument(0)).get("page");pages.add(p);
      assertTrue(((Number)store.get(7).get("dueAt")).longValue()>System.currentTimeMillis()+170000);
      if(p==2)service.command(7,"stop");
      return Map.of("total",350,"rows",rows((p-1)*100,100));
    });
    service.run(7);assertTrue(pages.containsAll(List.of(1,2)));verify(snapshots,never()).write(any(),anyLong(),anyMap());
    assertEquals("stopped",service.status(7).get("state"));
  }
  @Test void smallDatasetNeedsOnePageAndIncompleteDataNeverPasses()throws Exception{
    when(upstream.page(anyMap())).thenReturn(Map.of("total",42,"rows",rows(0,42)));
    assertEquals(42,((List<?>)service.collect(input(),"cookie").get("rows")).size());verify(upstream,times(1)).page(anyMap());
    doReturn(Map.of("total",300,"rows",rows(0,100)),
        Map.of("total",300,"rows",rows(100,99))).when(upstream).page(anyMap());
    assertThrows(IllegalArgumentException.class,()->service.collect(input(),"cookie"));
    doReturn(Map.of("total",0,"rows",List.of())).when(upstream).page(anyMap());
    assertThrows(IllegalArgumentException.class,()->service.collect(input(),"cookie"));
    doReturn(Map.of("total",300,"rows",rows(0,100)),Map.of("total",301,"rows",rows(100,100))).when(upstream).page(anyMap());
    assertThrows(IllegalArgumentException.class,()->service.collect(input(),"cookie"));
  }
  @Test void persistedScheduleRunsWithoutBrowserAndAfterServiceRestart()throws Exception{
    service.start(7,input());service.close();service=new BidServerSyncService(store,cipher,upstream,gdt,snapshots,rawStore,users);
    when(upstream.page(anyMap())).thenReturn(Map.of("total",1,"rows",rows(0,1)));
    service.run(7);
    verify(snapshots).write(eq(store.connection),eq(7L),anyMap());
    assertEquals("ready",service.status(7).get("state"));assertNotNull(service.status(7).get("lastSuccess"));
    assertTrue(((Number)store.get(7).get("dueAt")).longValue()>System.currentTimeMillis());
    service.run(7);verify(upstream,times(1)).page(anyMap());
  }
  @Test void stopAndReconfigurationInvalidateInFlightWork()throws Exception{
    service.start(7,input());
    when(upstream.page(anyMap())).thenAnswer(call->{service.command(7,"stop");return Map.of("total",1,"rows",rows(0,1));});
    service.run(7);verify(snapshots,never()).write(any(),anyLong(),anyMap());assertEquals("stopped",service.status(7).get("state"));
    service.start(7,input());
    doAnswer(call->{service.start(7,input());return Map.of("total",1,"rows",rows(0,1));}).when(upstream).page(anyMap());
    service.run(7);verify(snapshots,never()).write(any(),anyLong(),anyMap());assertEquals("waiting",service.status(7).get("state"));
  }
  @Test void authFailurePausesAndDoesNotExposeSecretsOrOverwriteSnapshot()throws Exception{
    service.start(7,input());
    when(upstream.page(anyMap())).thenThrow(new IllegalArgumentException("code=-1 private-test-cookie"));
    service.run(7);var status=service.status(7);assertEquals("paused",status.get("state"));assertFalse((Boolean)status.get("enabled"));
    assertTrue(status.get("error").toString().contains("code=-1"));assertFalse(status.toString().contains("private-test-cookie"));
    verify(snapshots,never()).write(any(),anyLong(),anyMap());
    service.command(7,"forget");assertFalse((Boolean)service.status(7).get("configured"));
  }
  @Test void revokedPermissionPreventsFetchAndCommit()throws Exception{
    service.start(7,input());when(users.canUseTool(any(),anyString())).thenReturn(false);service.run(7);
    verifyNoInteractions(upstream);assertEquals("paused",service.status(7).get("state"));
    when(users.canUseTool(any(),anyString())).thenReturn(true);service.start(7,input());
    when(upstream.page(anyMap())).thenAnswer(call->{when(users.canUseTool(any(),anyString())).thenReturn(false);return Map.of("total",1,"rows",rows(0,1));});
    service.run(7);verify(snapshots,never()).write(any(),anyLong(),anyMap());assertEquals("paused",service.status(7).get("state"));
  }
  @Test void endpointsRejectSwitchedAndUnauthenticatedWebsiteUser()throws Exception{
    var sessions=mock(SessionService.class);var mockService=mock(BidServerSyncService.class);
    var controller=new BidServerSyncController(sessions,mockService);var request=new MockHttpServletRequest();
    assertThrows(ResponseStatusException.class,()->controller.status(request));verifyNoInteractions(mockService);
    when(sessions.currentUser(request)).thenReturn(new UserRepository.UserAccount(7,"operator","hash","user",true,1,null,null,null));
    when(mockService.allowed(7)).thenReturn(true);
    assertThrows(ResponseStatusException.class,()->controller.start(Map.of("expectedUserId","8"),request));
    verify(mockService,never()).start(anyLong(),anyMap());
    assertThrows(ResponseStatusException.class,()->controller.prepareQuery(Map.of("expectedUserId","8"),request));
    assertThrows(ResponseStatusException.class,()->controller.page(Map.of("expectedUserId","8"),request));
    assertThrows(ResponseStatusException.class,()->controller.querySnapshot(Map.of("expectedUserId","8"),request));
    verify(mockService,never()).prepareQuery(anyLong(),anyMap());verify(mockService,never()).queryPage(anyLong(),anyMap());
    controller.prepareQuery(Map.of("expectedUserId","7"),request);verify(mockService).prepareQuery(eq(7L),anyMap());
    controller.page(Map.of("expectedUserId","7"),request);verify(mockService).queryPage(eq(7L),anyMap());
    controller.status(request);verify(mockService).status(7);
    controller.command("stop",Map.of("expectedUserId","7","userId","8"),request);verify(mockService).command(7,"stop");
  }
}
