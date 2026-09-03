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
  private final BidSnapshotController snapshots=mock(BidSnapshotController.class);
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
  @BeforeEach void setup(){
    var config=mock(RuntimeConfig.class);when(config.runtimeDir()).thenReturn(dir);
    cipher=new BidCredentialCipher(config);
    when(users.findById(anyLong())).thenAnswer(call->new UserRepository.UserAccount(call.getArgument(0),"operator","hash","user",true,1,null,null,null));
    when(users.canUseTool(any(),eq("bidMonitor"))).thenReturn(true);
    service=new BidServerSyncService(store,cipher,upstream,snapshots,users);
  }
  @AfterEach void close(){service.close();}
  private Map<String,Object> input(){return new LinkedHashMap<>(Map.of("cookie","userId=123; chuangliang_session=private-test-cookie", "clientUser","123","mainUserId","456","minutes",10,"createdDays",7));}
  private List<Map<String,Object>> rows(int offset,int size){
    var rows=new ArrayList<Map<String,Object>>();
    for(int i=0;i<size;i++)rows.add(new LinkedHashMap<>(Map.of("promotion_id","768107547558"+(offset+i),"promotion_name","plan","media_account_id","123",
        "advertiser_nick","account","stat_cost",1000-offset-i,"convert_cnt",2,"active_register",20,"cpa_bid",5,"cookie","must-drop")));
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
  @Test void readsAllFourPagesAndKeepsIdsAndMetrics()throws Exception{
    var pages=new ArrayList<Integer>();
    when(upstream.page(anyMap())).thenAnswer(call->{Map<String,Object> request=call.getArgument(0);int page=(Integer)request.get("page");pages.add(page);if(page>1)assertEquals(350L,request.get("total"));return Map.of("total",350,"rows",rows((page-1)*100,page==4?50:100));});
    var snapshot=service.collect(input(),input().get("cookie").toString());
    assertEquals(List.of(1,2,3,4),pages);assertEquals(350,((List<?>)snapshot.get("rows")).size());
    assertEquals("created_window_all",snapshot.get("selection"));
    var row=(Map<?,?>)((List<?>)snapshot.get("rows")).getFirst();assertEquals("account",row.get("media_account_name"));
    assertEquals("7681075475580",row.get("promotion_id"));assertFalse(snapshot.toString().contains("must-drop"));
  }
  @Test void creationWindowIncludesTodayAndThreePriorDates(){
    assertEquals(java.time.LocalDate.parse("2026-09-01"),BidServerSyncService.creationStart(java.time.LocalDate.parse("2026-09-04")));
    assertEquals(java.time.LocalDate.parse("2026-08-29"),BidServerSyncService.creationStart(java.time.LocalDate.parse("2026-09-01")));
    assertEquals(java.time.LocalDate.parse("2025-12-29"),BidServerSyncService.creationStart(java.time.LocalDate.parse("2026-01-01")));
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
    service.start(7,input());var pages=new ArrayList<Integer>();
    when(upstream.page(anyMap())).thenAnswer(call->{
      int p=(Integer)((Map<?,?>)call.getArgument(0)).get("page");pages.add(p);
      assertTrue(((Number)store.get(7).get("dueAt")).longValue()>System.currentTimeMillis()+170000);
      if(p==2)service.command(7,"stop");
      return Map.of("total",350,"rows",rows((p-1)*100,100));
    });
    service.run(7);assertEquals(List.of(1,2),pages);verify(snapshots,never()).write(any(),anyLong(),anyMap());
    assertEquals("stopped",service.status(7).get("state"));
  }
  @Test void smallDatasetNeedsOnePageAndIncompleteDataNeverPasses()throws Exception{
    when(upstream.page(anyMap())).thenReturn(Map.of("total",42,"rows",rows(0,42)));
    assertEquals(42,((List<?>)service.collect(input(),"cookie").get("rows")).size());verify(upstream,times(1)).page(anyMap());
    when(upstream.page(anyMap())).thenReturn(Map.of("total",300,"rows",rows(0,100)));
    assertThrows(IllegalArgumentException.class,()->service.collect(input(),"cookie"));
    when(upstream.page(anyMap())).thenReturn(Map.of("total",0,"rows",List.of()));
    assertThrows(IllegalArgumentException.class,()->service.collect(input(),"cookie"));
    when(upstream.page(anyMap())).thenReturn(Map.of("total",300,"rows",rows(0,100)),Map.of("total",301,"rows",rows(100,100)));
    assertThrows(IllegalArgumentException.class,()->service.collect(input(),"cookie"));
  }
  @Test void persistedScheduleRunsWithoutBrowserAndAfterServiceRestart()throws Exception{
    service.start(7,input());service.close();service=new BidServerSyncService(store,cipher,upstream,snapshots,users);
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
    controller.status(request);verify(mockService).status(7);
    controller.command("stop",Map.of("expectedUserId","7","userId","8"),request);verify(mockService).command(7,"stop");
  }
}
