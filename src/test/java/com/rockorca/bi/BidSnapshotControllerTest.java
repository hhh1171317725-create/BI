package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class BidSnapshotControllerTest {
  private Map<String, Object> row() {
    return new HashMap<>(Map.of("promotion_id", "1874489175001681", "media_account_id", "7676449794404745237",
        "stat_cost", 10, "convert_cnt", 2, "active_register", 4, "cpa_bid", 5, "cookie", "must-not-save"));
  }
  private Map<String, Object> input(List<?> rows) {
    return Map.of("date", LocalDate.now(ReportService.BEIJING).toString(), "rows", rows);
  }
  @Test void validatesSnapshotAndDropsUnknownFields() {
    var result=BidSnapshotController.validate(input(List.of(row())));
    var saved=(Map<?,?>)((List<?>)result.get("rows")).getFirst();
    assertFalse(saved.containsKey("cookie"));assertEquals("7676449794404745237",saved.get("media_account_id"));
  }

  @Test void retainsCreationScopeAndRejectsInvalidRanges() {
    var data=new HashMap<>(input(List.of(row())));
    var date=LocalDate.now(ReportService.BEIJING);
    data.put("createdStart",date.minusDays(6).toString());data.put("createdEnd",date.toString());
    assertEquals(date.minusDays(6).toString(),BidSnapshotController.validate(data).get("createdStart"));
    data.put("createdStart",date.minusDays(100).toString());
    assertThrows(IllegalArgumentException.class,()->BidSnapshotController.validate(data));
  }
  @Test void rejectsEmptyDuplicateAndMissingMetrics() {
    assertThrows(IllegalArgumentException.class,()->BidSnapshotController.validate(input(List.of())));
    assertThrows(IllegalArgumentException.class,()->BidSnapshotController.validate(input(List.of(row(),row()))));
    var bad=row();bad.remove("cpa_bid");
    assertThrows(IllegalArgumentException.class,()->BidSnapshotController.validate(input(List.of(bad))));
    assertThrows(IllegalArgumentException.class,()->BidSnapshotController.validate(Map.of("date","2000-01-01","rows",List.of(row()))));
  }
  @Test void rejectsSwitchedWebsiteUserBeforeDatabaseWrite() {
    var sessions=mock(SessionService.class);var reports=mock(ReportRepository.class);
    var request=new MockHttpServletRequest();
    var actor=new UserRepository.UserAccount(2,"operator","hash","user",true,1,null,null,null);
    when(sessions.currentUser(request)).thenReturn(actor);
    var controller=new BidSnapshotController(sessions,reports,new ObjectMapper());
    assertThrows(ResponseStatusException.class,()->controller.save(Map.of("expectedUserId","1"),request));
    verifyNoInteractions(reports);
  }

  @Test void readsOnlyAuthenticatedOwnersSnapshot() throws Exception {
    var sessions=mock(SessionService.class);var reports=mock(ReportRepository.class);
    var request=new MockHttpServletRequest();
    when(sessions.currentUser(request)).thenReturn(new UserRepository.UserAccount(7,"operator","hash","user",true,1,null,null,null));
    var connection=mock(java.sql.Connection.class);
    when(reports.openConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(mock(java.sql.Statement.class));
    var query=mock(java.sql.PreparedStatement.class);
    when(connection.prepareStatement("SELECT payload FROM bid_monitor_snapshots WHERE user_id=?")).thenReturn(query);
    when(query.executeQuery()).thenReturn(mock(java.sql.ResultSet.class));
    request.setParameter("userId","1");
    var result=new BidSnapshotController(sessions,reports,new ObjectMapper()).get(request);
    assertEquals("7",result.get("userId"));verify(query).setLong(1,7);
  }

  @Test void writesOnlySanitizedSnapshotToAuthenticatedOwner() throws Exception {
    var sessions=mock(SessionService.class);var reports=mock(ReportRepository.class);
    var request=new MockHttpServletRequest();
    when(sessions.currentUser(request)).thenReturn(new UserRepository.UserAccount(7,"operator","hash","user",true,1,null,null,null));
    var connection=mock(java.sql.Connection.class);when(reports.openConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(mock(java.sql.Statement.class));
    var insert=mock(java.sql.PreparedStatement.class);when(connection.prepareStatement(anyString())).thenReturn(insert);
    var data=new HashMap<>(input(List.of(row())));data.put("expectedUserId","7");
    var result=new BidSnapshotController(sessions,reports,new ObjectMapper()).save(data,request);
    assertEquals(1,result.get("count"));verify(insert).setLong(1,7);
    var payload=org.mockito.ArgumentCaptor.forClass(String.class);verify(insert).setString(eq(2),payload.capture());
    assertFalse(payload.getValue().contains("must-not-save"));verify(insert).executeUpdate();
  }

  @Test void unauthenticatedRequestsNeverAccessDatabase() {
    var sessions=mock(SessionService.class);var reports=mock(ReportRepository.class);
    var controller=new BidSnapshotController(sessions,reports,new ObjectMapper());
    assertThrows(ResponseStatusException.class,()->controller.get(new MockHttpServletRequest()));
    verifyNoInteractions(reports);
  }
}
