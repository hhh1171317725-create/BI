package com.rockorca.bi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;

/** Reconciles a complete owner/day response inside the caller's locked transaction. */
final class BidRowPersistence {
  enum Table {
    PROVIDER("bid_monitor_provider_rows",true), HISTORY("bid_monitor_history_rows",false);
    final String name;
    final boolean advertiser;
    Table(String name,boolean advertiser){this.name=name;this.advertiser=advertiser;}
  }
  record Key(String platform,String account,String plan) {}
  record Row(Key key,String advertiser,String payload) {}
  record Existing(String hash,String advertiser) {}
  record Changes(int written,int removed,int unchanged) {}

  static Changes replace(Connection connection,Table table,long owner,String date,List<Row> rows)throws Exception{
    // Validate before any mutation; a duplicate must not silently become last-write-wins.
    var incoming=new HashMap<Key,String>();
    var digest=MessageDigest.getInstance("SHA-256");
    for(var row:rows){
      String hash=HexFormat.of().formatHex(digest.digest(row.payload().getBytes(StandardCharsets.UTF_8)));
      if(incoming.putIfAbsent(row.key(),hash)!=null)throw new IllegalArgumentException("上游返回重复计划，未覆盖旧数据");
    }
    var existing=new HashMap<Key,Existing>();
    String scope=" WHERE user_id=? AND report_date=?";
    // Read only identities and digests, not all LONGTEXT payloads into JVM memory.
    try(var query=connection.prepareStatement("SELECT source_platform,media_account_id,promotion_id,SHA2(payload,256) AS payload_hash"
        +(table.advertiser?",advertiser_id":"")+" FROM "+table.name+scope)){
      query.setLong(1,owner);query.setString(2,date);query.setFetchSize(Integer.MIN_VALUE);
      try(var result=query.executeQuery()){
        while(result.next())existing.put(new Key(result.getString("source_platform"),result.getString("media_account_id"),result.getString("promotion_id")),
            new Existing(result.getString("payload_hash"),table.advertiser?result.getString("advertiser_id"):""));
      }
    }
    var added=new ArrayList<Row>();var changed=new ArrayList<Row>();int removed=0,unchanged=0;
    for(var row:rows){
      var previous=existing.remove(row.key());
      if(previous==null)added.add(row);
      else if(Objects.equals(previous.hash(),incoming.get(row.key()))
          &&(!table.advertiser||Objects.equals(previous.advertiser(),row.advertiser())))unchanged++;
      else changed.add(row);
    }
    // Delete disappeared identities first: case-insensitive MySQL keys may equate
    // a renamed incoming identity with an old one. Never delete after inserting it.
    if(!existing.isEmpty()){
      try(var delete=connection.prepareStatement("DELETE FROM "+table.name+scope+" AND source_platform=? AND media_account_id=? AND promotion_id=?")){
        for(var key:existing.keySet()){
          bindKey(delete,1,owner,date,key);delete.addBatch();
          if(++removed%500==0)delete.executeBatch();
        }
        if(removed%500!=0)delete.executeBatch();
      }
    }
    if(!added.isEmpty()){
      String sql="INSERT INTO "+table.name+"(user_id,report_date,source_platform,media_account_id,promotion_id,payload"
          +(table.advertiser?",advertiser_id":"")+") VALUES (?,?,?,?,?,?"+(table.advertiser?",?":"")+")";
      try(var insert=connection.prepareStatement(sql)){
        int count=0;
        for(var row:added){
          bindKey(insert,1,owner,date,row.key());insert.setString(6,row.payload());
          if(table.advertiser)insert.setString(7,row.advertiser());
          insert.addBatch();if(++count%500==0)insert.executeBatch();
        }
        if(count%500!=0)insert.executeBatch();
      }
    }
    if(!changed.isEmpty()){
      try(var update=connection.prepareStatement("UPDATE "+table.name+" SET payload=?"+(table.advertiser?",advertiser_id=?":"")
          +scope+" AND source_platform=? AND media_account_id=? AND promotion_id=?")){
        int count=0;
        for(var row:changed){
          update.setString(1,row.payload());if(table.advertiser)update.setString(2,row.advertiser());
          bindKey(update,table.advertiser?3:2,owner,date,row.key());update.addBatch();
          if(++count%500==0)update.executeBatch();
        }
        if(count%500!=0)update.executeBatch();
      }
    }
    return new Changes(added.size()+changed.size(),removed,unchanged);
  }

  private static void bindKey(java.sql.PreparedStatement statement,int offset,long owner,String date,Key key)throws Exception{
    statement.setLong(offset,owner);statement.setString(offset+1,date);statement.setString(offset+2,key.platform());
    statement.setString(offset+3,key.account());statement.setString(offset+4,key.plan());
  }
}
