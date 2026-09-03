package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class BidDingtalkServiceTest {
  @TempDir Path dir;
  final BidServerSyncServiceTest.MemoryStore store=new BidServerSyncServiceTest.MemoryStore();
  final BidSnapshotController snapshots=mock(BidSnapshotController.class);
  final BidServerSyncService sync=mock(BidServerSyncService.class);
  final DingtalkRobotClient robot=mock(DingtalkRobotClient.class);
  final MutableClock clock=new MutableClock();
  final ObjectMapper mapper=new ObjectMapper();
  BidDingtalkService service;
  static class MutableClock extends Clock{
    Instant now=Instant.parse("2026-09-03T09:59:00Z");
    @Override public ZoneId getZone(){return ZoneOffset.UTC;}
    @Override public Clock withZone(ZoneId zone){return this;}
    @Override public Instant instant(){return now;}
  }
  List<Map<String,Object>> rules(){return List.of(Map.of("name","taskA","keyword","account-A","price","10"),Map.of("name","taskB","keyword","account-B","price","20"));}
  Map<String,Object> input(){return new LinkedHashMap<>(Map.of("revision","","pricingRevision","p1","tasks",List.of("taskA","taskB"),"time","18:00","enabled",true,
      "webhook","https://oapi.dingtalk.com/robot/send?access_token=test-token-only","secret","SECtestsecret123","keyword","TOP5"));}
  Map<String,Object> row(String id,String account,int cost){return new LinkedHashMap<>(Map.of("promotion_id",id,"promotion_name","plan-"+id,"media_account_id","7676449794404745237","media_account_name",account,"stat_cost",cost,"convert_cnt",10,"active_register",20,"cpa_bid",10));}
  Map<String,Object> snapshot(){return Map.of("date","2026-09-03","updatedAt",clock.instant().minusSeconds(60).toString(),"selection","spend_desc_top_400","rows",List.of(row("1","account-A",150),row("2","account-B",120)));}
  @BeforeEach void setup()throws Exception{
    var config=mock(RuntimeConfig.class);when(config.runtimeDir()).thenReturn(dir);
    service=new BidDingtalkService(store,new BidCredentialCipher(config),snapshots,sync,robot,mapper,clock);
    store.update(7,(connection,state)->{state.put("taskRules",rules());state.put("pricingRevision","p1");state.put("dueAt",123L);});
    when(sync.allowed(7)).thenReturn(true);when(snapshots.readOwned(7)).thenAnswer(call->snapshot());
  }
  @AfterEach void close(){service.close();}
  @Test void encryptedConfigurationIsOwnerScopedAndDoesNotAlterDataSync()throws Exception{
    var result=service.save(7,input());
    assertEquals("18:00",result.get("time"));assertEquals(List.of("taskA","taskB"),result.get("tasks"));
    assertEquals(Instant.parse("2026-09-03T10:00:00Z").toEpochMilli(),result.get("dueAt"));
    assertFalse(store.get(7).toString().contains("test-token-only"));assertFalse(result.toString().contains("SECtestsecret123"));
    assertEquals(false,service.settings(8).get("configured"));assertEquals(123L,store.get(7).get("dueAt"));
    var reused=input();reused.put("webhook","");reused.put("secret","");reused.put("revision",result.get("revision"));
    service.save(7,reused);service.send(7,false);
    verify(robot,times(2)).send(eq(input().get("webhook").toString()),eq(input().get("secret").toString()),eq("TOP5"),anyString());
  }
  @Test void previewUsesOwnedSnapshotWithoutSending()throws Exception{
    service.save(7,input());var preview=service.preview(7);
    assertEquals(2,((List<?>)preview.get("messages")).size());verifyNoInteractions(robot);
    assertThrows(IllegalArgumentException.class,()->service.preview(8));verify(snapshots,never()).readOwned(8);
  }
  @Test void dailyClaimSurvivesRestartAndDoesNotDuplicate()throws Exception{
    service.save(7,input());service.send(7,true);verifyNoInteractions(robot);
    clock.now=Instant.parse("2026-09-03T10:00:01Z");
    var result=service.send(7,true);assertEquals("sent",result.get("state"));
    assertEquals(Instant.parse("2026-09-04T10:00:00Z").toEpochMilli(),result.get("dueAt"));
    service.close();var config=mock(RuntimeConfig.class);when(config.runtimeDir()).thenReturn(dir);
    service=new BidDingtalkService(store,new BidCredentialCipher(config),snapshots,sync,robot,mapper,clock);
    service.send(7,true);verify(robot,times(2)).send(anyString(),anyString(),anyString(),anyString());
  }
  @Test void staleSnapshotOrRevokedPermissionNeverSends()throws Exception{
    service.save(7,input());when(snapshots.readOwned(7)).thenReturn(Map.of("date","2026-09-02","updatedAt",clock.instant().toString()));
    assertEquals("failed",service.send(7,false).get("state"));verifyNoInteractions(robot);
    clock.now=clock.now.plusSeconds(301);when(sync.allowed(7)).thenReturn(false);
    assertEquals("failed",service.send(7,false).get("state"));verifyNoInteractions(robot);
  }
  @Test void partialFailureIsRecordedAndNeverAutomaticallyRetried()throws Exception{
    service.save(7,input());clock.now=Instant.parse("2026-09-03T10:00:00Z");
    doNothing().doThrow(new IllegalArgumentException("发送结果未确认")).when(robot).send(anyString(),anyString(),anyString(),anyString());
    var result=service.send(7,true);assertEquals("failed",result.get("state"));assertTrue(result.get("lastResult").toString().startsWith("已发送 1 个任务"));
    service.send(7,true);assertThrows(IllegalArgumentException.class,()->service.send(7,false));
    verify(robot,times(2)).send(anyString(),anyString(),anyString(),anyString());
  }
  @Test void clearingConfigurationStopsRemainingTasks()throws Exception{
    service.save(7,input());
    doAnswer(call->{service.forget(7);return null;}).when(robot).send(anyString(),anyString(),anyString(),anyString());
    service.send(7,false);verify(robot,times(1)).send(anyString(),anyString(),anyString(),anyString());
    assertEquals(false,service.settings(7).get("configured"));assertEquals(0L,service.settings(7).get("dueAt"));
  }
  @Test void changedTaskPricesStopRemainingMessages()throws Exception{
    service.save(7,input());
    doAnswer(call->{store.update(7,(connection,state)->state.put("pricingRevision","p2"));return null;}).when(robot).send(anyString(),anyString(),anyString(),anyString());
    var result=service.send(7,false);assertEquals("failed",result.get("state"));
    verify(robot,times(1)).send(anyString(),anyString(),anyString(),anyString());
  }
  @Test void springCanConstructScheduledServiceWithoutUsingLiveDatabase(){
    try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
      context.registerBean(BidServerSyncStore.class,()->store);
      var config=mock(RuntimeConfig.class);when(config.runtimeDir()).thenReturn(dir);
      context.registerBean(BidCredentialCipher.class,()->new BidCredentialCipher(config));
      context.registerBean(BidSnapshotController.class,()->snapshots);context.registerBean(BidServerSyncService.class,()->sync);
      context.registerBean(DingtalkRobotClient.class,()->robot);context.registerBean(ObjectMapper.class,()->mapper);
      context.register(BidDingtalkService.class);context.refresh();assertNotNull(context.getBean(BidDingtalkService.class));
    }
  }
  @Test void conflictingConfigurationAndUnknownTasksAreRejected()throws Exception{
    service.save(7,input());assertThrows(IllegalArgumentException.class,()->service.save(7,input()));
    var invalid=input();invalid.put("tasks",List.of("missing"));invalid.put("revision",service.settings(7).get("revision"));
    assertThrows(IllegalArgumentException.class,()->service.save(7,invalid));
    assertThrows(IllegalArgumentException.class,()->service.save(8,input()));
  }
  @Test void scheduleUsesBeijingTimeAndSkipsOldMissedSlots()throws Exception{
    assertEquals(Instant.parse("2026-09-04T10:00:00Z").toEpochMilli(),BidDingtalkService.nextDue(Instant.parse("2026-09-03T10:00:00Z"),"18:00"));
    service.save(7,input());clock.now=Instant.parse("2026-09-04T01:00:00Z");service.send(7,true);
    verifyNoInteractions(robot);assertEquals(Instant.parse("2026-09-04T10:00:00Z").toEpochMilli(),service.settings(7).get("dueAt"));
    clock.now=Instant.parse("2026-09-04T11:00:00Z");service.send(7,true);verifyNoInteractions(robot);
  }
  @Test void formatterSelectsTopFiveWithinEachUnambiguousTask(){
    var rows=new ArrayList<Map<String,Object>>();for(int i=1;i<=8;i++)rows.add(row(Integer.toString(i),"account-A",i*100));
    rows.add(row("99","account-A-account-B",9999));rows.add(row("100","account-B",9000));
    var snapshot=new LinkedHashMap<>(snapshot());snapshot.put("rows",rows);
    var messages=BidTop5Formatter.messages(snapshot,rules(),List.of("taskA","taskB"));
    String text=messages.getFirst().get("text");
    assertEquals(6,text.lines().count());assertEquals("【taskA TOP5】",text.lines().findFirst().orElseThrow());
    assertEquals("1. 消耗 800.00 | 回传 50.00% | 出价 10.00 | 出价利润 50.00% | 优化师 -- | 账户ID 7676449794404745237 | 计划ID 8",text.lines().skip(1).findFirst().orElseThrow());
    for(int i=0;i<5;i++)assertTrue(text.lines().skip(i+1).findFirst().orElseThrow().startsWith((i+1)+". 消耗 "+(800-i*100)+".00"));
    assertFalse(text.contains("300.00"));assertFalse(text.contains("9999.00"));assertFalse(text.contains("9000.00"));
    assertFalse(text.contains("ROI"));assertTrue(text.contains("账户ID"));assertTrue(text.contains("计划ID"));
    assertEquals(2,messages.get(1).get("text").lines().count());assertTrue(messages.get(1).get("text").contains("消耗 9000.00"));
  }
  @Test void formatterRetainsOptimizerAndExactIdsOnOneLine(){
    var row=row("7681075475582042163","account-A",150);
    row.put("user_name","张三\r\n运营\u2028A\u2029B");
    var data=new LinkedHashMap<>(snapshot());data.put("rows",List.of(row));
    String text=BidTop5Formatter.messages(data,rules(),List.of("taskA")).getFirst().get("text");
    assertEquals(2,text.lines().count());assertFalse(text.contains("\u2028"));assertFalse(text.contains("\u2029"));
    assertTrue(text.contains("优化师 张三  运营 A B"));
    assertTrue(text.contains("账户ID 7676449794404745237 | 计划ID 7681075475582042163"));
    row.put("user_name"," ");row.remove("media_account_id");
    text=BidTop5Formatter.messages(data,rules(),List.of("taskA")).getFirst().get("text");
    assertTrue(text.contains("优化师 -- | 账户ID --"));
  }
  @Test void formatterFinancialRulesMatchBoundaryAndZeroCases(){
    var row=row("1","account-A",150);var metrics=BidTop5Formatter.metrics(row,new java.math.BigDecimal("10"));
    assertEquals("2.000",metrics.get("roi"));assertEquals("50.00",metrics.get("grant"));assertEquals("20.00",metrics.get("line"));assertEquals("50.00%",metrics.get("rate"));
    row.put("stat_cost",120);assertEquals("0.00",BidTop5Formatter.metrics(row,java.math.BigDecimal.TEN).get("grant"));
    row.put("stat_cost",150);row.put("convert_cnt",6);assertEquals("0.00",BidTop5Formatter.metrics(row,java.math.BigDecimal.TEN).get("grant"));
    row.put("stat_cost",0);assertEquals("--",BidTop5Formatter.metrics(row,java.math.BigDecimal.TEN).get("roi"));
    row.put("convert_cnt",0);assertEquals("--",BidTop5Formatter.metrics(row,java.math.BigDecimal.TEN).get("line"));
    assertEquals("0.00%",BidTop5Formatter.metrics(row,java.math.BigDecimal.TEN).get("ratio"));
    row.put("active_register",0);assertEquals("--",BidTop5Formatter.metrics(row,java.math.BigDecimal.TEN).get("ratio"));
  }
  @Test void robotRejectsForeignDestinationsAndMissingSuccessCode()throws Exception{
    var client=new DingtalkRobotClient(mapper);
    assertThrows(IllegalArgumentException.class,()->DingtalkRobotClient.validate("http://127.0.0.1/robot/send?access_token=abcdabcd",""));
    assertThrows(IllegalArgumentException.class,()->DingtalkRobotClient.validate(input().get("webhook")+"&url=http://127.0.0.1",""));
    assertThrows(IllegalArgumentException.class,()->client.checkResponse(200,"{}"));
    assertThrows(IllegalArgumentException.class,()->client.checkResponse(200,"null"));
    assertThrows(IllegalArgumentException.class,()->client.checkResponse(200,"{\"errcode\":310000,\"errmsg\":\"secret\"}"));
    assertDoesNotThrow(()->client.checkResponse(200,"{\"errcode\":0}"));
    var uri=DingtalkRobotClient.signed(input().get("webhook").toString(),"SECtestsecret123",1234567L);
    assertEquals("oapi.dingtalk.com",uri.getHost());assertTrue(uri.toString().contains("&timestamp=1234567&sign="));
    assertFalse(uri.toString().contains("SECtestsecret123"));
  }
  @Test void controllerRejectsWebsiteIdentitySwitches()throws Exception{
    var sessions=mock(SessionService.class);var controller=new BidDingtalkController(sessions,sync,service);var request=new MockHttpServletRequest();
    assertThrows(ResponseStatusException.class,()->controller.settings(request));
    when(sessions.currentUser(request)).thenReturn(new UserRepository.UserAccount(7,"op","hash","user",true,1,null,null,null));
    assertThrows(ResponseStatusException.class,()->controller.send(Map.of("expectedUserId","8"),request));
    assertThrows(ResponseStatusException.class,()->controller.preview(Map.of("expectedUserId","8"),request));verifyNoInteractions(robot);
  }
}
