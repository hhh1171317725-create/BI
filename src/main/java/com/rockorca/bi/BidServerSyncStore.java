package com.rockorca.bi;

import java.sql.Connection;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class BidServerSyncStore {
  private final ReportRepository reports;
  private final ObjectMapper mapper;
  private volatile boolean initialized;
  public BidServerSyncStore(ReportRepository reports, ObjectMapper mapper) { this.reports=reports; this.mapper=mapper; }

  private synchronized void initialize() throws Exception {
    if (initialized) return;
    try (var connection=reports.openConnection(); var statement=connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS bid_monitor_server_sync (user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, payload TEXT NOT NULL, due_at BIGINT NOT NULL DEFAULT 0, INDEX bid_sync_due(due_at)) ENGINE=InnoDB");
    }
    initialized=true;
  }

  @FunctionalInterface interface Update { void apply(Connection connection, Map<String,Object> state) throws Exception; }

  Map<String,Object> get(long owner) throws Exception {
    initialize();
    try (var connection=reports.openConnection()) { return read(connection,owner,false); }
  }

  private Map<String,Object> read(Connection connection,long owner,boolean lock) throws Exception {
    try (var statement=connection.prepareStatement("SELECT payload FROM bid_monitor_server_sync WHERE user_id=?"+(lock?" FOR UPDATE":""))) {
      statement.setLong(1,owner);
      try (var result=statement.executeQuery()) {
        return result.next()?mapper.readValue(result.getString(1),new TypeReference<Map<String,Object>>(){}):new LinkedHashMap<>();
      }
    }
  }

  // The row lock also protects snapshot commits against concurrent stop/reconfigure requests.
  Map<String,Object> update(long owner,Update action) throws Exception {
    initialize();
    try (var connection=reports.openConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var insert=connection.prepareStatement("INSERT IGNORE INTO bid_monitor_server_sync(user_id,payload,due_at) VALUES (?,'{}',0)")) {
          insert.setLong(1,owner);insert.executeUpdate();
        }
        var state=read(connection,owner,true);
        action.apply(connection,state);
        String payload=mapper.writeValueAsString(state);
        if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000)
          throw new IllegalArgumentException("配置内容过大，请缩短任务名称或账户关键词");
        try (var statement=connection.prepareStatement("UPDATE bid_monitor_server_sync SET payload=?,due_at=? WHERE user_id=?")) {
          statement.setString(1,payload);
          statement.setLong(2,((Number)state.getOrDefault("dueAt",0L)).longValue());
          statement.setLong(3,owner);statement.executeUpdate();
        }
        connection.commit();return state;
      } catch (Exception error) { connection.rollback();throw error; }
    }
  }

  List<Long> due(long now) throws Exception {
    initialize();
    try (var connection=reports.openConnection();var statement=connection.prepareStatement(
        "SELECT user_id FROM bid_monitor_server_sync WHERE due_at>0 AND due_at<=? ORDER BY due_at LIMIT 2")) {
      statement.setLong(1,now);
      try (var result=statement.executeQuery()) {
        var ids=new ArrayList<Long>();while(result.next())ids.add(result.getLong(1));return ids;
      }
    }
  }

  List<Long> dingtalkDue(long now) throws Exception {
    initialize();
    try(var connection=reports.openConnection();var statement=connection.prepareStatement(
        "SELECT user_id FROM bid_monitor_server_sync WHERE CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.dingDueAt')) AS UNSIGNED)>0 AND CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.dingDueAt')) AS UNSIGNED)<=? ORDER BY CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.dingDueAt')) AS UNSIGNED) LIMIT 2")) {
      statement.setLong(1,now);
      try(var result=statement.executeQuery()){
        var ids=new ArrayList<Long>();while(result.next())ids.add(result.getLong(1));return ids;
      }
    }
  }
}
