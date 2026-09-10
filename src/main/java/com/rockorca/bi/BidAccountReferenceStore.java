package com.rockorca.bi;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Derived account/task/price/gap data, versioned by committed daily-report imports. */
@Component
public class BidAccountReferenceStore {
  private final ReportRepository repository;
  private final ObjectMapper mapper;
  private boolean initialized;
  public BidAccountReferenceStore(ReportRepository repository,ObjectMapper mapper){this.repository=repository;this.mapper=mapper;}

  public String revision(){
    try(var connection=repository.openConnection();var statement=connection.prepareStatement(
        "SELECT COUNT(*),COALESCE(MAX(id),0) FROM report_sync_runs WHERE status='success' AND report_type IN ('dhh','all')");var rows=statement.executeQuery()){
      rows.next();return "v2:"+rows.getLong(1)+":"+rows.getLong(2);
    }catch(Exception error){throw new IllegalStateException("读取日报版本失败",error);}
  }
  private synchronized void initialize() throws Exception {
    if(initialized)return;
    try(var connection=repository.openConnection();var statement=connection.createStatement()){
      statement.execute("CREATE TABLE IF NOT EXISTS bid_account_references (anchor DATE PRIMARY KEY, source_revision VARCHAR(100) NOT NULL, payload LONGTEXT NOT NULL) ENGINE=InnoDB");
    }
    initialized=true;
  }
  public Map<String,Object> read(String anchor,String revision){
    try{
      initialize();
      try(var connection=repository.openConnection();var statement=connection.prepareStatement("SELECT payload FROM bid_account_references WHERE anchor=? AND source_revision=?")){
        statement.setString(1,anchor);statement.setString(2,revision);
        try(var rows=statement.executeQuery()){return rows.next()?mapper.readValue(rows.getString(1),new TypeReference<Map<String,Object>>(){}):null;}
      }
    }catch(Exception error){throw new IllegalStateException("读取账户任务单价预计算结果失败",error);}
  }
  public void save(String anchor,String revision,Map<String,Object> data){
    try{
      initialize();
      try(var connection=repository.openConnection();var statement=connection.prepareStatement("INSERT INTO bid_account_references(anchor,source_revision,payload) VALUES (?,?,?) ON DUPLICATE KEY UPDATE source_revision=VALUES(source_revision),payload=VALUES(payload)")){
        statement.setString(1,anchor);statement.setString(2,revision);statement.setString(3,mapper.writeValueAsString(data));statement.executeUpdate();
      }
    }catch(Exception error){throw new IllegalStateException("保存账户任务单价预计算结果失败",error);}
  }
}
