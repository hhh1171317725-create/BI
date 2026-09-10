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
    when(snapshots.readOwned(1)).thenReturn(Map.of("updatedAt","time1","rows",List.of(Map.of("promotion_id","123"))));
    when(store.get(1)).thenReturn(Map.of("credential","secret-cookie","clientUser","secret-client", "dingCredential","secret-robot",
        "taskRules",List.of(Map.of("name","shared-task","price","2")),"pricingRevision","p1",
        "strategies",List.of(Map.of("id","s1","name","测试策略","note","放量测试","accounts",List.of())),"strategyRevision","s1"));
    var controller=new BidSharedReportController(sessions,accounts,users,snapshots,store,config);
    var result=controller.get(request,"");
    assertEquals("2",result.get("userId"));assertEquals("1",result.get("sharedOwnerId"));assertEquals(false,result.get("canManage"));
    assertTrue(result.toString().contains("123"));assertTrue(result.toString().contains("shared-task"));assertTrue(result.toString().contains("测试策略"));assertFalse(result.toString().contains("secret"));
    assertNull(controller.get(request,"1:time1").get("snapshot"));
    verify(snapshots,never()).readOwned(2);verify(store,never()).get(2);
    when(sessions.currentUser(request)).thenReturn(admin);assertEquals(true,controller.get(request,"").get("canManage"));
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
