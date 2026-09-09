package com.rockorca.bi;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Stores the provider response separately from the small, shared report snapshot. */
@Component
public class BidProviderRawStore {
  private static final int MAX_ROW_BYTES=1_000_000;
  private final ReportRepository reports;
  private final ObjectMapper mapper;
  private volatile boolean initialized;

  public BidProviderRawStore(ReportRepository reports,ObjectMapper mapper){this.reports=reports;this.mapper=mapper;}

  synchronized void initialize() throws Exception {
    if(initialized)return;
    try(var connection=reports.openConnection();var statement=connection.createStatement()){
      statement.execute("CREATE TABLE IF NOT EXISTS bid_monitor_provider_rows ("
          +"user_id BIGINT UNSIGNED NOT NULL,report_date DATE NOT NULL,source_platform VARCHAR(16) NOT NULL,"
          +"media_account_id VARCHAR(100) NOT NULL,advertiser_id VARCHAR(100) NOT NULL,promotion_id VARCHAR(100) NOT NULL,"
          +"payload LONGTEXT NOT NULL,updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
          +"PRIMARY KEY(user_id,report_date,source_platform,media_account_id,promotion_id),"
          +"INDEX bid_provider_account(user_id,report_date,source_platform,advertiser_id)) ENGINE=InnoDB");
    }
    initialized=true;
  }

  void replace(Connection connection,long owner,String date,List<Map<String,Object>> rows)throws Exception{
    LocalDate.parse(date);
    try(var delete=connection.prepareStatement("DELETE FROM bid_monitor_provider_rows WHERE user_id=? AND report_date=?")){
      delete.setLong(1,owner);delete.setString(2,date);delete.executeUpdate();
    }
    try(var insert=connection.prepareStatement("INSERT INTO bid_monitor_provider_rows(user_id,report_date,source_platform,media_account_id,advertiser_id,promotion_id,payload) VALUES (?,?,?,?,?,?,?)")){
      for(var row:rows){
        if(!(row.get("provider_data") instanceof Map<?,?> provider))throw new IllegalArgumentException("上游原始计划字段缺失，未覆盖旧数据");
        String payload=mapper.writeValueAsString(provider);
        if(payload.getBytes(StandardCharsets.UTF_8).length>MAX_ROW_BYTES)throw new IllegalArgumentException("单条上游计划数据超过 1 MB，未覆盖旧数据");
        insert.setLong(1,owner);insert.setString(2,date);insert.setString(3,text(row,"source_platform"));
        insert.setString(4,text(row,"media_account_id"));insert.setString(5,text(row,"advertiser_id"));insert.setString(6,text(row,"promotion_id"));
        insert.setString(7,payload);insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
}
