package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class UserRepositoryGarminPermissionTest {
  @Test
  void existingMembersWithoutAnOverrideCanUseGarmin() throws Exception {
    var database = new Database();

    assertEquals(Boolean.TRUE, database.repository.toolVisibility(7).get("garmin"));

    verify(database.query).setLong(1, 7);
    verify(database.insert, never()).executeBatch();
  }

  @Test
  void readsAnExplicitGarminOptOutFromJdbc() throws Exception {
    var database = new Database();
    database.stored.put(7L, new LinkedHashMap<>(Map.of("garmin", false)));

    assertEquals(Boolean.FALSE, database.repository.toolVisibility(7).get("garmin"));

    verify(database.insert, never()).executeBatch();
  }

  @Test
  void savesGarminOptOutInTheJdbcBatchAndReadsItBack() throws Exception {
    var database = new Database();

    var saved = database.repository.saveToolVisibility(7, Map.of("garmin", false));

    assertTrue(database.executed.contains(new Permission(7, "garmin", false)));
    assertEquals(Boolean.FALSE, database.stored.get(7L).get("garmin"));
    assertEquals(Boolean.FALSE, saved.get("garmin"));
    assertEquals(Boolean.FALSE, new UserRepository(database.reports).toolVisibility(7).get("garmin"));
    assertEquals(Boolean.TRUE, database.repository.toolVisibility(8).get("garmin"));
    verify(database.insert).executeBatch();
    verify(database.reports, never()).openConnection();
  }

  private record Permission(long userId, String tool, boolean visible) {}

  /** Models only JDBC parameter binding, batch execution and returned rows. */
  private static final class Database {
    final ReportRepository reports = mock(ReportRepository.class);
    final Connection connection = mock(Connection.class);
    final PreparedStatement insert = mock(PreparedStatement.class);
    final PreparedStatement query = mock(PreparedStatement.class);
    final UserRepository repository = new UserRepository(reports);
    final Map<Long, Map<String, Boolean>> stored = new HashMap<>();
    final List<Permission> executed = new ArrayList<>();

    Database() throws Exception {
      when(reports.openControlConnection()).thenReturn(connection);
      when(connection.prepareStatement(contains("INSERT INTO report_user_tool_visibility"))).thenReturn(insert);
      when(connection.prepareStatement(contains("SELECT tool_key, visible"))).thenReturn(query);

      var parameters = new HashMap<Integer, Object>();
      var pending = new ArrayList<Permission>();
      doAnswer(call -> { parameters.put(call.getArgument(0), call.getArgument(1)); return null; })
          .when(insert).setLong(anyInt(), anyLong());
      doAnswer(call -> { parameters.put(call.getArgument(0), call.getArgument(1)); return null; })
          .when(insert).setString(anyInt(), anyString());
      doAnswer(call -> { parameters.put(call.getArgument(0), call.getArgument(1)); return null; })
          .when(insert).setBoolean(anyInt(), anyBoolean());
      doAnswer(call -> {
        pending.add(new Permission((Long) parameters.get(1), (String) parameters.get(2), (Boolean) parameters.get(3)));
        return null;
      }).when(insert).addBatch();
      when(insert.executeBatch()).thenAnswer(call -> {
        int[] counts = new int[pending.size()];
        for (int index = 0; index < pending.size(); index++) {
          var row = pending.get(index);
          stored.computeIfAbsent(row.userId(), key -> new LinkedHashMap<>()).put(row.tool(), row.visible());
          executed.add(row);
          counts[index] = 1;
        }
        pending.clear();
        return counts;
      });

      var selectedUser = new AtomicLong();
      doAnswer(call -> { selectedUser.set(call.getArgument(1)); return null; }).when(query).setLong(eq(1), anyLong());
      when(query.executeQuery()).thenAnswer(call -> {
        var rows = List.copyOf(stored.getOrDefault(selectedUser.get(), Map.of()).entrySet());
        var cursor = new AtomicInteger(-1);
        var result = mock(ResultSet.class);
        when(result.next()).thenAnswer(read -> cursor.incrementAndGet() < rows.size());
        when(result.getString("tool_key")).thenAnswer(read -> rows.get(cursor.get()).getKey());
        when(result.getBoolean("visible")).thenAnswer(read -> rows.get(cursor.get()).getValue());
        return result;
      });
    }
  }
}
