package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class DhhSelectiveReadTest {
  @Test
  void lightweightReadKeepsAlertDayAccountsAndBindsAllParameters() throws Exception {
    ReportRepository repository = mock(ReportRepository.class, CALLS_REAL_METHODS);
    HikariDataSource source = mock(HikariDataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    ReflectionTestUtils.setField(repository, "dataSource", source);
    ReflectionTestUtils.setField(repository, "objectMapper", new ObjectMapper());
    when(source.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true, true, false);
    when(result.getObject("business_date")).thenReturn("2026-09-01", "2026-09-07");
    when(result.getString("account_info")).thenReturn("""
        [{"账户ID":"123","账户名称":"测试账户","消耗":150}]
        """);
    var rows = repository.readDhhRows("2026-09-01", "2026-09-07", "2026-09-07", "123", false);
    assertFalse(rows.getFirst().containsKey("账户列表"));
    assertTrue(rows.getLast().containsKey("账户列表"));
    verify(result, times(1)).getString("account_info");
    verify(statement).setString(1, "2026-09-07");
    var sql = ArgumentCaptor.forClass(String.class);
    verify(connection).prepareStatement(sql.capture());
    assertTrue(sql.getValue().contains("CASE WHEN business_date = ?"));
    var indexes = ArgumentCaptor.forClass(Integer.class);
    var values = ArgumentCaptor.forClass(String.class);
    verify(statement, atLeastOnce()).setString(indexes.capture(), values.capture());
    assertEquals(sql.getValue().chars().filter(c -> c == '?').count(), indexes.getAllValues().size());
    assertEquals("123", values.getAllValues().getLast());
    ReportService service = new ReportService(null, null, null, new ObjectMapper());
    var alerts = service.buildDhhAlerts(rows, "2026-09-07");
    assertEquals(true, alerts.get("hasAccountData"));
    assertEquals(1, alerts.get("accountCount"));
  }
}
