package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BidRowPersistenceTest {
  static BidRowPersistence.Row row(String platform,String account,String id,String advertiser,String payload){
    return new BidRowPersistence.Row(new BidRowPersistence.Key(platform,account,id),advertiser,payload);
  }
  static BidRowPersistence.Row row(String id,String payload){return row("gdt","account",id,"advertiser",payload);}
  record Write(String sql,Map<Integer,Object> parameters) {}
  static class Database {
    final Connection connection=mock(Connection.class);
    final PreparedStatement query=mock(PreparedStatement.class);
    final List<Write> writes=new ArrayList<>();
    int batches;
    Database(List<BidRowPersistence.Row> old)throws Exception{
      var cursor=new AtomicInteger(-1);var result=mock(ResultSet.class);
      when(query.executeQuery()).thenAnswer(call->{cursor.set(-1);return result;});
      when(result.next()).thenAnswer(call->cursor.incrementAndGet()<old.size());
      when(result.getString(anyString())).thenAnswer(call->{
        var row=old.get(cursor.get());
        return switch((String)call.getArgument(0)){
          case "source_platform"->row.key().platform();case "media_account_id"->row.key().account();
          case "promotion_id"->row.key().plan();case "advertiser_id"->row.advertiser();
          case "payload_hash"->HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(row.payload().getBytes(StandardCharsets.UTF_8)));
          default->throw new AssertionError(call.getArgument(0).toString());
        };
      });
      when(connection.prepareStatement(anyString())).thenAnswer(call->{
        String sql=call.getArgument(0);if(sql.startsWith("SELECT"))return query;
        var statement=mock(PreparedStatement.class);var parameters=new HashMap<Integer,Object>();
        var pending=new ArrayList<Write>();
        doAnswer(c->{parameters.put(c.getArgument(0),c.getArgument(1));return null;}).when(statement).setString(anyInt(),anyString());
        doAnswer(c->{parameters.put(c.getArgument(0),c.getArgument(1));return null;}).when(statement).setLong(anyInt(),anyLong());
        doAnswer(c->{pending.add(new Write(sql,Map.copyOf(parameters)));return null;}).when(statement).addBatch();
        when(statement.executeBatch()).thenAnswer(c->{batches++;int count=pending.size();writes.addAll(pending);pending.clear();return new int[count];});
        return statement;
      });
    }
  }

  @Test void identicalResponsesProduceNoMutationIncludingUnicodeAndLargeIds()throws Exception{
    var rows=List.of(row("7685374192209395758","{\"任务\":\"促活🚀\",\"reg_pv\":123}"));
    var db=new Database(rows);
    assertEquals(new BidRowPersistence.Changes(0,0,1),BidRowPersistence.replace(db.connection,BidRowPersistence.Table.PROVIDER,7,"2026-09-28",rows));
    assertTrue(db.writes.isEmpty());assertEquals(0,db.batches);
    verify(db.connection,never()).prepareStatement(startsWith("INSERT"));
    verify(db.connection,never()).prepareStatement(startsWith("UPDATE"));
    verify(db.connection,never()).prepareStatement(startsWith("DELETE"));
    verify(db.query).setLong(1,7);verify(db.query).setString(2,"2026-09-28");
  }

  @Test void reconcilesChangedAddedAndRemovedPlansWithoutRewritingOtherPlans()throws Exception{
    var db=new Database(List.of(row("keep","{}"),row("changed","{\"reg_pv\":1}"),row("gone","{}")));
    var result=BidRowPersistence.replace(db.connection,BidRowPersistence.Table.PROVIDER,9,"2026-09-27",
        List.of(row("keep","{}"),row("changed","{\"reg_pv\":2}"),row("new","{\"all_original_fields\":true}")));
    assertEquals(new BidRowPersistence.Changes(2,1,1),result);assertEquals(3,db.writes.size());
    var deletion=db.writes.get(0);assertTrue(deletion.sql().endsWith("AND source_platform=? AND media_account_id=? AND promotion_id=?"));
    assertEquals(Map.of(1,9L,2,"2026-09-27",3,"gdt",4,"account",5,"gone"),deletion.parameters());
    var insertion=db.writes.stream().filter(w->w.sql().startsWith("INSERT")).findFirst().orElseThrow();
    assertEquals("new",insertion.parameters().get(5));assertEquals("{\"all_original_fields\":true}",insertion.parameters().get(6));
    var update=db.writes.stream().filter(w->w.sql().startsWith("UPDATE")).findFirst().orElseThrow();
    assertEquals(Map.of(1,"{\"reg_pv\":2}",2,"advertiser",3,9L,4,"2026-09-27",5,"gdt",6,"account",7,"changed"),update.parameters());
    verify(db.connection,never()).commit();verify(db.connection,never()).setAutoCommit(anyBoolean());
  }

  @Test void advertiserCorrectionIsPersistedEvenWhenRawPayloadIsUnchanged()throws Exception{
    var db=new Database(List.of(row("gdt","a","p","old","{}")));
    var result=BidRowPersistence.replace(db.connection,BidRowPersistence.Table.PROVIDER,7,"2026-09-28",List.of(row("gdt","a","p","correct","{}")));
    assertEquals(1,result.written());assertEquals("correct",db.writes.getFirst().parameters().get(2));
  }

  @Test void duplicateInputFailsBeforeTouchingDatabase()throws Exception{
    var db=new Database(List.of());
    assertThrows(IllegalArgumentException.class,()->BidRowPersistence.replace(db.connection,BidRowPersistence.Table.PROVIDER,7,"2026-09-28",List.of(row("p","{}"),row("p","{\"new\":true}"))));
    verifyNoInteractions(db.connection);
  }

  @Test void samePlanIdInDifferentAccountsAndPlatformsStaysSeparate()throws Exception{
    var a=row("gdt","a","p","x","{}");var b=row("gdt","b","p","x","{}");var c=row("bytedance","a","p","x","{}");
    var db=new Database(List.of(a,b,c));
    var result=BidRowPersistence.replace(db.connection,BidRowPersistence.Table.HISTORY,7,"2026-09-28",List.of(a,b,c));
    assertEquals(3,result.unchanged());assertTrue(db.writes.isEmpty());
  }

  @Test void historyUpdatesUseCompleteKeyAndPreserveTransactionFailure()throws Exception{
    var db=new Database(List.of(row("p","{}")));
    BidRowPersistence.replace(db.connection,BidRowPersistence.Table.HISTORY,7,"2026-09-28",List.of(row("p","{\"convert_cnt\":6}")));
    assertEquals(Map.of(1,"{\"convert_cnt\":6}",2,7L,3,"2026-09-28",4,"gdt",5,"account",6,"p"),db.writes.getFirst().parameters());
    assertFalse(db.writes.getFirst().sql().contains("advertiser_id"));
    var broken=mock(PreparedStatement.class);when(db.connection.prepareStatement(startsWith("UPDATE"))).thenReturn(broken);
    when(broken.executeBatch()).thenThrow(new SQLException("disk full"));
    assertThrows(SQLException.class,()->BidRowPersistence.replace(db.connection,BidRowPersistence.Table.HISTORY,7,"2026-09-28",List.of(row("p","{\"convert_cnt\":6}"))));
    verify(db.connection,never()).commit();
  }

  @Test void emptyCompleteResponseRemovesOnlyPreviouslySelectedIdentities()throws Exception{
    var db=new Database(List.of(row("old","{}")));
    assertEquals(new BidRowPersistence.Changes(0,1,0),BidRowPersistence.replace(db.connection,BidRowPersistence.Table.HISTORY,7,"2026-09-28",List.of()));
    assertEquals(1,db.writes.size());assertTrue(db.writes.getFirst().sql().startsWith("DELETE"));
  }

  @Test void largeResponsesUseBoundedBatches()throws Exception{
    var rows=new ArrayList<BidRowPersistence.Row>();for(int i=0;i<1001;i++)rows.add(row("p"+i,"{}"));
    var db=new Database(List.of());
    assertEquals(1001,BidRowPersistence.replace(db.connection,BidRowPersistence.Table.PROVIDER,7,"2026-09-28",rows).written());
    assertEquals(3,db.batches);assertEquals(1001,db.writes.size());
  }

  @Test void providerStorePreservesAllOriginalFields()throws Exception{
    var db=new Database(List.of());var mapper=new tools.jackson.databind.ObjectMapper();
    var original=Map.of("reg_pv",123,"future_field",Map.of("nested",List.of("未知字段","原始值")));
    var store=new BidProviderRawStore(mock(ReportRepository.class),mapper);
    store.replace(db.connection,7,"2026-09-28",List.of(Map.of("source_platform","gdt","media_account_id","a",
        "advertiser_id","ad","promotion_id","p","provider_data",original)));
    assertEquals(mapper.writeValueAsString(original),db.writes.getFirst().parameters().get(6));
  }

  @Test void invalidProviderRowCannotRemoveExistingSnapshot()throws Exception{
    var db=new Database(List.of(row("old","{}")));
    var store=new BidProviderRawStore(mock(ReportRepository.class),new tools.jackson.databind.ObjectMapper());
    assertThrows(IllegalArgumentException.class,()->store.replace(db.connection,7,"2026-09-28",List.of(Map.of("promotion_id","p"))));
    verifyNoInteractions(db.connection);
  }
}
