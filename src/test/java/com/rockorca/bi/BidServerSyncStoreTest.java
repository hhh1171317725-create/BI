package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BidServerSyncStoreTest {
  @Test void dingtalkScheduleQueryIsSeparateFromDataSyncDueTime()throws Exception{
    var reports=mock(ReportRepository.class);var connection=mock(Connection.class);
    when(reports.openConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(mock(Statement.class));
    var statement=mock(PreparedStatement.class);when(connection.prepareStatement(contains("$.dingDueAt"))).thenReturn(statement);
    var result=mock(ResultSet.class);when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true,false);when(result.getLong(1)).thenReturn(7L);
    var store=new BidServerSyncStore(reports,new ObjectMapper());
    assertEquals(java.util.List.of(7L),store.dingtalkDue(1000L));verify(statement).setLong(1,1000L);
    verify(connection,never()).prepareStatement(contains("WHERE due_at>0"));
  }
  @Test void writesScheduleAndPayloadInSameLockedTransaction()throws Exception{
    var reports=mock(ReportRepository.class);var connection=mock(Connection.class);
    when(reports.openConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(mock(Statement.class));
    var insert=mock(PreparedStatement.class);var read=mock(PreparedStatement.class);var update=mock(PreparedStatement.class);
    when(connection.prepareStatement(startsWith("INSERT"))).thenReturn(insert);
    when(connection.prepareStatement("SELECT payload FROM bid_monitor_server_sync WHERE user_id=? FOR UPDATE")).thenReturn(read);
    when(connection.prepareStatement(startsWith("UPDATE"))).thenReturn(update);
    var result=mock(ResultSet.class);when(read.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);when(result.getString(1)).thenReturn("{\"enabled\":true,\"dueAt\":10}");
    var store=new BidServerSyncStore(reports,new ObjectMapper());
    var saved=store.update(7,(c,state)->{assertSame(connection,c);assertEquals(true,state.get("enabled"));state.put("dueAt",123L);});
    assertEquals(123L,saved.get("dueAt"));verify(read).setLong(1,7);verify(insert).setLong(1,7);
    verify(update).setLong(2,123L);verify(update).setLong(3,7);
    var order=inOrder(connection,read,update);order.verify(connection).setAutoCommit(false);
    order.verify(read).executeQuery();order.verify(update).executeUpdate();order.verify(connection).commit();verify(connection,never()).rollback();
  }
  @Test void failedSnapshotCommitRollsBackScheduleToo()throws Exception{
    var reports=mock(ReportRepository.class);var connection=mock(Connection.class);
    when(reports.openConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(mock(Statement.class));
    var statement=mock(PreparedStatement.class);when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
    var store=new BidServerSyncStore(reports,new ObjectMapper());
    assertThrows(SQLException.class,()->store.update(7,(c,state)->{throw new SQLException("snapshot failed");}));
    verify(connection).rollback();verify(connection,never()).commit();
  }
  @Test void oldExtensionCannotOverwriteEnabledServerSnapshot()throws Exception{
    var sessions=mock(SessionService.class);var reports=mock(ReportRepository.class);
    var connection=mock(Connection.class);when(reports.openConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(mock(Statement.class));
    var store=new BidServerSyncServiceTest.MemoryStore();
    store.states.put(7L,new java.util.HashMap<>(Map.of("enabled",true)));
    var request=new org.springframework.mock.web.MockHttpServletRequest();
    when(sessions.currentUser(request)).thenReturn(new UserRepository.UserAccount(7,"operator","hash","user",true,1,null,null,null));
    var controller=new BidSnapshotController(sessions,reports,new ObjectMapper(),store);
    var input=Map.<String,Object>of("expectedUserId","7","date",java.time.LocalDate.now(ReportService.BEIJING).toString(),
        "rows",java.util.List.of(Map.of("promotion_id","123","stat_cost",1,"convert_cnt",1,"active_register",1,"cpa_bid",1)));
    var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.save(input,request));
    assertEquals(409,error.getStatusCode().value());verify(store.connection,never()).prepareStatement(anyString());
  }
}
