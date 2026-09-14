package com.rockorca.bi;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Daily, normalized plan facts used by the time-dimension report. */
@Component
public class BidHistoryStore {
  private static final int MAX_QUERY_ROWS=200_000;
  private final ReportRepository reports;
  private final ObjectMapper mapper;
  private volatile boolean initialized;

  public BidHistoryStore(ReportRepository reports,ObjectMapper mapper){this.reports=reports;this.mapper=mapper;}

  synchronized void initialize()throws Exception{
    if(initialized)return;
    try(var connection=reports.openConnection();var statement=connection.createStatement()){
      statement.execute("CREATE TABLE IF NOT EXISTS bid_monitor_history_rows ("
          +"user_id BIGINT UNSIGNED NOT NULL,report_date DATE NOT NULL,source_platform VARCHAR(16) NOT NULL,"
          +"media_account_id VARCHAR(100) NOT NULL,promotion_id VARCHAR(100) NOT NULL,payload TEXT NOT NULL,"
          +"updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
          +"PRIMARY KEY(user_id,report_date,source_platform,media_account_id,promotion_id),"
          +"INDEX bid_history_date(user_id,report_date)) ENGINE=InnoDB");
    }
    initialized=true;
  }

  void replace(Connection connection,long owner,LocalDate date,List<Map<String,Object>> rows)throws Exception{
    initialize();
    var clean=BidSnapshotController.cleanRows(rows,true);
    try(var delete=connection.prepareStatement("DELETE FROM bid_monitor_history_rows WHERE user_id=? AND report_date=?")){
      delete.setLong(1,owner);delete.setString(2,date.toString());delete.executeUpdate();
    }
    try(var insert=connection.prepareStatement("INSERT INTO bid_monitor_history_rows(user_id,report_date,source_platform,media_account_id,promotion_id,payload) VALUES (?,?,?,?,?,?)")){
      for(var row:clean){
        insert.setLong(1,owner);insert.setString(2,date.toString());insert.setString(3,text(row,"source_platform"));
        insert.setString(4,text(row,"media_account_id"));insert.setString(5,text(row,"promotion_id"));
        insert.setString(6,mapper.writeValueAsString(row));insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  List<Map<String,Object>> read(long owner,LocalDate start,LocalDate end)throws Exception{
    return read(owner,start,end,false);
  }

  List<Map<String,Object>> readConversions(long owner,LocalDate start,LocalDate end)throws Exception{
    return read(owner,start,end,true);
  }

  private List<Map<String,Object>> read(long owner,LocalDate start,LocalDate end,boolean conversionsOnly)throws Exception{
    initialize();
    // Preserve both account IDs and the displayed platform used by frontend planIdentity.
    String projection=conversionsOnly?"JSON_OBJECT('promotion_id',promotion_id,'media_account_id',media_account_id,"
        +"'source_platform',source_platform,'advertiser_id',JSON_EXTRACT(payload,'$.advertiser_id'),"
        +"'platform_text',JSON_EXTRACT(payload,'$.platform_text'),'convert_cnt',JSON_EXTRACT(payload,'$.convert_cnt')) AS payload":"payload";
    try(var connection=reports.openConnection()){
      try(var query=connection.prepareStatement("SELECT report_date,"+projection+" FROM bid_monitor_history_rows WHERE user_id=? AND report_date BETWEEN ? AND ? ORDER BY report_date DESC LIMIT "+(MAX_QUERY_ROWS+1))){
        query.setLong(1,owner);query.setString(2,start.toString());query.setString(3,end.toString());
        // MySQL streams rows instead of buffering the entire payload in the JDBC driver.
        query.setFetchSize(Integer.MIN_VALUE);
        try(var result=query.executeQuery()){
          var rows=new ArrayList<Map<String,Object>>();
          while(result.next()){
            if(rows.size()==MAX_QUERY_ROWS)throw new IllegalArgumentException("历史计划超过 200000 条，请缩小时间范围");
            var row=mapper.readValue(result.getString("payload"),new TypeReference<Map<String,Object>>(){});
            row.put("report_date",result.getString("report_date"));rows.add(row);
          }
          return rows;
        }
      }
    }
  }

  private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
}
