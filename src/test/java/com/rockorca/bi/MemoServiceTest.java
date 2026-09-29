package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MemoServiceTest {
  ReportRepository reports;
  Connection connection;
  PreparedStatement statement;
  ResultSet rows;
  MemoService service;
  AtomicReference<String> query;

  @BeforeEach void setup() throws Exception {
    reports = mock(ReportRepository.class); connection = mock(Connection.class);
    statement = mock(PreparedStatement.class); rows = mock(ResultSet.class);
    when(reports.openConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(mock(Statement.class));
    query = new AtomicReference<>();
    when(connection.prepareStatement(anyString())).thenAnswer(call -> { query.set(call.getArgument(0)); return statement; });
    when(connection.prepareStatement(anyString(), anyInt())).thenAnswer(call -> { query.set(call.getArgument(0)); return statement; });
    when(statement.executeQuery()).thenReturn(rows);
    service = new MemoService(reports);
  }

  @Test void searchUsesOwnerAndLiteralWildcardsAndBoundedPreview() throws Exception {
    service.list(42, "预算 20%_", "投放", "all", 30);
    assertTrue(query.get().contains("user_id=? AND deleted=?"));
    assertTrue(query.get().contains("LEFT(content,180)"));
    assertTrue(query.get().contains("LIMIT 31 OFFSET ?"));
    verify(statement).setObject(1, 42L); verify(statement).setObject(2, false);
    verify(statement).setObject(3, "投放"); verify(statement).setObject(4, "%预算%");
    verify(statement).setObject(7, "%20!%!_%"); verify(statement).setObject(10, 30);
  }

  @Test void missingOrOtherOwnersNoteIsNotReadable() throws Exception {
    var error = assertThrows(ResponseStatusException.class, () -> service.get(42, 777));
    assertEquals(404, error.getStatusCode().value());
    assertTrue(query.get().contains("id=? AND user_id=?"));
    verify(statement).setLong(1, 777); verify(statement).setLong(2, 42);
  }

  @Test void staleOrForeignUpdateRollsBackInsteadOfOverwriting() throws Exception {
    var error = assertThrows(ResponseStatusException.class, () -> service.save(42, 777L,
        Map.of("title", "笔记", "content", "内容", "tags", "投放", "pinned", false, "version", 3)));
    assertEquals(409, error.getStatusCode().value());
    assertTrue(query.get().contains("WHERE user_id=? AND id=? AND version=? AND deleted=FALSE"));
    verify(statement).setLong(6, 42); verify(statement).setLong(7, 777); verify(statement).setLong(8, 3);
    verify(connection).rollback(); verify(connection, never()).commit();
  }

  @Test void softDeleteAndRestoreRequireOwnerVersionAndPreviousState() throws Exception {
    when(statement.executeUpdate()).thenReturn(1);
    service.trash(42, 777, 3, true);
    assertTrue(query.get().contains("id=? AND user_id=? AND version=? AND deleted=?"));
    verify(statement).setBoolean(1, true); verify(statement).setLong(4, 42);
    verify(statement).setLong(5, 3); verify(statement).setBoolean(6, false);
    clearInvocations(statement);
    service.trash(42, 777, 4, false);
    verify(statement).setBoolean(1, false); verify(statement).setBoolean(6, true);
  }

  @Test void createCommitsAndReadsOnSameConnectionWithoutNestedPoolAcquisition() throws Exception {
    when(statement.executeUpdate()).thenReturn(1);
    ResultSet keys = mock(ResultSet.class); when(keys.next()).thenReturn(true); when(keys.getLong(1)).thenReturn(777L);
    when(statement.getGeneratedKeys()).thenReturn(keys);
    when(rows.next()).thenReturn(true); when(rows.getLong("id")).thenReturn(777L);
    assertEquals(777L, service.save(42, null, Map.of("title", "笔记", "content", "原文\n😀", "tags", "常用，常用", "pinned", true)).get("id"));
    verify(connection).commit(); verify(connection, never()).rollback();
    // One connection for lazy schema initialization, one for the complete transaction.
    verify(reports, times(2)).openConnection(); verify(statement).setString(3, "常用");
  }

  @Test void tagsAreNormalizedAndSearchIsEscaped() {
    assertEquals("账户,投放", MemoService.tags(" 账户，投放\n账户, "));
    assertEquals("%a!!b!%!_%", MemoService.literalLike("a!b%_"));
    assertThrows(IllegalArgumentException.class, () -> MemoService.tags("a".repeat(25)));
    assertThrows(IllegalArgumentException.class, () -> MemoService.tags("1,2,3,4,5,6,7,8,9,10,11"));
    assertThrows(IllegalArgumentException.class, () -> MemoService.version(1.5));
  }

  @Test void invalidInputNeverTouchesDatabase() {
    assertThrows(IllegalArgumentException.class, () -> service.save(42, null, Map.of("title", "  ")));
    assertThrows(IllegalArgumentException.class, () -> service.save(42, null, Map.of("title", "信息", "content", "x".repeat(50001))));
    assertThrows(IllegalArgumentException.class, () -> service.list(42, "", "", "unknown", 0));
    assertThrows(IllegalArgumentException.class, () -> service.list(42, "", "", "all", -1));
    verifyNoInteractions(reports);
  }
}
