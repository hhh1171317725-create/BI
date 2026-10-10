package com.rockorca.bi;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BidSharedReportTest {
  private UserRepository.UserAccount user(long id,String role){return new UserRepository.UserAccount(id,"user"+id,"hash",role,true,1,null,null,null);}
  @Test void memberReceivesAdminDataAndPricesButNoCredentials()throws Exception{
    var sessions=mock(SessionService.class);var accounts=mock(UserRepository.class);var users=mock(UserService.class);
    var snapshots=mock(BidSnapshotController.class);var store=mock(BidServerSyncStore.class);var config=mock(RuntimeConfig.class);
    var admin=user(1,"admin");var member=user(2,"user");var request=new MockHttpServletRequest();
    when(sessions.currentUser(request)).thenReturn(member);when(accounts.list()).thenReturn(List.of(admin,member));
    when(users.canUseTool(any(),eq("bidMonitor"))).thenReturn(true);when(config.get("BID_SHARED_OWNER_ID","")).thenReturn("1");
    when(accounts.findById(1)).thenReturn(Optional.of(admin));
    when(snapshots.readOwnedSince(1,"")).thenReturn(new BidSnapshotController.SnapshotRead("1:time1",Map.of("updatedAt","time1","rows",List.of(Map.of("promotion_id","123")))));
    when(snapshots.readOwnedSince(1,"1:time1")).thenReturn(new BidSnapshotController.SnapshotRead("1:time1",null));
    var state=new LinkedHashMap<String,Object>(Map.of("credential","secret-cookie","clientUser","secret-client", "dingCredential","secret-robot",
        "taskRules",List.of(Map.of("name","shared-task","price","2")),"pricingRevision","p1",
        "strategies",List.of(Map.of("id","s1","name","测试策略","note","放量测试","accounts",List.of())),"strategyRevision","s1"));
    state.putAll(Map.of("state","paused","error","服务器同步失败（上游 code=-1），已暂停并保留旧快照", "failureAt","2026-10-10T01:00:00Z",
        "failureReason","广点通 · 第 2 页：上游 HTTP 503（已尝试 3 次）","lastFailureAt","2026-10-10T01:10:00Z","failureCount",2L,
        "lastSuccess","2026-10-09T23:00:00Z","enabled",false,"dueAt",0L));
    state.putAll(Map.of("historyState","retrying","historyError","昨日历史归档失败，10 分钟后自动重试",
        "historyFailureAt","2026-10-10T00:30:00Z","historyLastDate","2026-10-08","historyLastSuccess","2026-10-09T00:30:00Z","historyRetryAt",123L));
    when(store.get(1)).thenReturn(state);
    when(store.get(2)).thenReturn(Map.of("state","ready","error","wrong viewer status"));
    var controller=new BidSharedReportController(sessions,accounts,users,snapshots,store,config);
    var result=controller.get(request,"");
    assertEquals("2",result.get("userId"));assertEquals("1",result.get("sharedOwnerId"));assertEquals(false,result.get("canManage"));
    assertTrue(result.toString().contains("123"));assertTrue(result.toString().contains("shared-task"));assertTrue(result.toString().contains("测试策略"));assertFalse(result.toString().contains("secret"));
    var status=(Map<?,?>)result.get("status");
    assertEquals("paused",status.get("state"));assertEquals(state.get("error"),status.get("error"));
    assertEquals(state.get("failureAt"),status.get("failureAt"));assertEquals(state.get("lastSuccess"),status.get("lastSuccess"));
    for(String key:List.of("failureReason","lastFailureAt","failureCount"))assertEquals(state.get(key),status.get(key));
    assertEquals("time1",status.get("snapshotUpdatedAt"));assertFalse(status.toString().contains("secret"));
    for(String key:List.of("historyState","historyError","historyFailureAt","historyLastDate","historyLastSuccess","historyRetryAt"))
      assertEquals(state.get(key),status.get(key));
    var unchanged=controller.get(request,"1:time1");assertNull(unchanged.get("snapshot"));assertEquals(status,unchanged.get("status"));
    verify(snapshots,never()).readOwned(2);verify(store,never()).get(2);
    when(sessions.currentUser(request)).thenReturn(admin);assertEquals(true,controller.get(request,"").get("canManage"));
    verify(accounts,never()).list();verify(snapshots,never()).readOwned(1);
    when(accounts.findById(1)).thenReturn(Optional.empty());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.get(request,"1:time1"));
  }
  @Test void nextConditionalReadReturnsTheCommittedSnapshotAfterStatusReadCrossesItsCommit()throws Exception{
    var sessions=mock(SessionService.class);var accounts=mock(UserRepository.class);var users=mock(UserService.class);
    var snapshots=mock(BidSnapshotController.class);var store=mock(BidServerSyncStore.class);var config=mock(RuntimeConfig.class);
    var request=new MockHttpServletRequest();when(sessions.currentUser(request)).thenReturn(user(2,"user"));
    when(config.get("BID_SHARED_OWNER_ID","")).thenReturn("7");when(accounts.findById(7)).thenReturn(Optional.of(user(7,"admin")));
    when(users.canUseTool(any(),eq("bidMonitor"))).thenReturn(true);
    // A commit between the snapshot and status reads may expose v1 with lastSuccess=v2.
    // Keep v1 as the conditional cursor so the next poll still receives the v2 payload.
    when(snapshots.readOwnedSince(7,"7:v1")).thenReturn(new BidSnapshotController.SnapshotRead("7:v1",null),
        new BidSnapshotController.SnapshotRead("7:v2",Map.of("updatedAt","v2","rows",List.of(Map.of("promotion_id","123")))));
    when(store.get(7)).thenReturn(Map.of("state","ready","error","","lastSuccess","v2"));
    var controller=new BidSharedReportController(sessions,accounts,users,snapshots,store,config);
    var crossed=controller.get(request,"7:v1");var crossedStatus=(Map<?,?>)crossed.get("status");
    assertEquals("7:v1",crossed.get("version"));assertNull(crossed.get("snapshot"));
    assertEquals("v1",crossedStatus.get("snapshotUpdatedAt"));assertEquals("v2",crossedStatus.get("lastSuccess"));
    var next=controller.get(request,crossed.get("version").toString());var nextStatus=(Map<?,?>)next.get("status");
    assertEquals("7:v2",next.get("version"));assertEquals("v2",((Map<?,?>)next.get("snapshot")).get("updatedAt"));
    assertEquals(nextStatus.get("lastSuccess"),nextStatus.get("snapshotUpdatedAt"));
  }
  @Test void sharedStatusRecoversEvenWhenTheSnapshotVersionIsUnchanged()throws Exception{
    var sessions=mock(SessionService.class);var accounts=mock(UserRepository.class);var users=mock(UserService.class);
    var snapshots=mock(BidSnapshotController.class);var store=mock(BidServerSyncStore.class);var config=mock(RuntimeConfig.class);
    var request=new MockHttpServletRequest();when(sessions.currentUser(request)).thenReturn(user(2,"user"));
    when(config.get("BID_SHARED_OWNER_ID","")).thenReturn("7");when(accounts.findById(7)).thenReturn(Optional.of(user(7,"admin")));
    when(users.canUseTool(any(),eq("bidMonitor"))).thenReturn(true);
    when(snapshots.readOwnedSince(7,"7:2026-10-10T01:00:00Z")).thenReturn(new BidSnapshotController.SnapshotRead("7:2026-10-10T01:00:00Z",null));
    when(store.get(7)).thenReturn(Map.of("state","retrying","enabled",true,"error","读取失败","failureAt","2026-10-10T02:00:00Z",
        "failureReason","字节 · 第 2 页：网络请求超时","lastFailureAt","2026-10-10T02:10:00Z","failureCount",2L),
        Map.of("state","ready","enabled",true,"error","","lastSuccess","2026-10-10T01:00:00Z"));
    var controller=new BidSharedReportController(sessions,accounts,users,snapshots,store,config);
    var failed=controller.get(request,"7:2026-10-10T01:00:00Z");var recovered=controller.get(request,"7:2026-10-10T01:00:00Z");
    assertNull(failed.get("snapshot"));assertNull(recovered.get("snapshot"));
    assertEquals("读取失败",((Map<?,?>)failed.get("status")).get("error"));
    assertEquals("字节 · 第 2 页：网络请求超时",((Map<?,?>)failed.get("status")).get("failureReason"));
    assertEquals(2L,((Map<?,?>)failed.get("status")).get("failureCount"));
    var status=(Map<?,?>)recovered.get("status");assertEquals("",status.get("error"));assertFalse(status.containsKey("failureAt"));
    for(String key:List.of("failureReason","lastFailureAt","failureCount"))assertFalse(status.containsKey(key));
    assertEquals("2026-10-10T01:00:00Z",status.get("snapshotUpdatedAt"));verify(store,never()).get(2);
  }
  @Test void adoptsExistingAdminSnapshotOnceInsteadOfChoosingEmptyAccount()throws Exception{
    var sessions=mock(SessionService.class);var accounts=mock(UserRepository.class);var users=mock(UserService.class);
    var snapshots=mock(BidSnapshotController.class);var store=mock(BidServerSyncStore.class);var config=mock(RuntimeConfig.class);
    when(config.get("BID_SHARED_OWNER_ID","")).thenReturn("");when(accounts.list()).thenReturn(List.of(user(1,"admin"),user(3,"admin")));
    when(snapshots.readOwned(1)).thenReturn(Map.of());when(snapshots.readOwned(3)).thenReturn(Map.of("rows",List.of(Map.of("id","plan"))));
    var controller=new BidSharedReportController(sessions,accounts,users,snapshots,store,config);
    assertEquals(3,controller.source().id());verify(config).saveBidSharedOwner(3);
  }
}
