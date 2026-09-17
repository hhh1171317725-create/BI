package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BidHistoryControllerTest {
  @Test void memberReadsSharedOwnersHistoryWithinBoundedPastRange()throws Exception{
    var sessions=mock(SessionService.class);var users=mock(UserService.class);
    var shared=mock(BidSharedReportController.class);var history=mock(BidHistoryStore.class);
    var request=new MockHttpServletRequest();
    var member=new UserRepository.UserAccount(2,"member","hash","user",true,1,null,null,null);
    var admin=new UserRepository.UserAccount(1,"admin","hash","admin",true,1,null,null,null);
    when(sessions.currentUser(request)).thenReturn(member);when(users.canUseTool(any(),eq("bidMonitor"))).thenReturn(true);
    when(shared.source()).thenReturn(admin);
    LocalDate yesterday=LocalDate.now(ReportService.BEIJING).minusDays(1),start=yesterday.minusDays(6);
    when(history.read(1,start,yesterday)).thenReturn(List.of(Map.of("promotion_id","123","report_date",yesterday.toString())));
    var warnings=mock(BidEndedWarningService.class);
    var controller=new BidHistoryController(sessions,users,shared,history,warnings);
    var result=controller.get(request,start.toString(),yesterday.toString());
    assertEquals(1,result.get("count"));verify(history).read(1,start,yesterday);verify(history,never()).read(eq(2L),any(),any());
    when(history.readConversions(1,start,yesterday)).thenReturn(List.of(Map.of("promotion_id","123","convert_cnt",6)));
    assertEquals(1,controller.conversions(request,start.toString(),yesterday.toString()).get("count"));
    verify(history).readConversions(1,start,yesterday);
    verify(history,never()).readConversions(eq(2L),any(),any());
    when(warnings.read(1,false)).thenReturn(Map.of("count",1));
    assertEquals(1,controller.endedWarnings(request,false).get("count"));
    verify(warnings).read(1,false);verify(warnings,never()).read(eq(2L),anyBoolean());
    when(users.canUseTool(member,"bidMonitor")).thenReturn(false);
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.endedWarnings(request,false));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.conversions(request,start.toString(),yesterday.toString()));
    when(users.canUseTool(member,"bidMonitor")).thenReturn(true);
    assertThrows(IllegalArgumentException.class,()->controller.get(request,start.minusDays(40).toString(),yesterday.toString()));
    assertThrows(IllegalArgumentException.class,()->controller.get(request,start.toString(),LocalDate.now(ReportService.BEIJING).toString()));
  }
}
