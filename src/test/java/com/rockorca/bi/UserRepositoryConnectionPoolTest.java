package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

class UserRepositoryConnectionPoolTest {
  @Test
  void userLookupUsesReservedControlPool() throws Exception {
    ReportRepository reports = mock(ReportRepository.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet result = mock(ResultSet.class);
    when(reports.openControlConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(false);

    assertTrue(new UserRepository(reports).findByUsername("hhh").isEmpty());

    verify(reports).openControlConnection();
    verify(reports, never()).openConnection();
  }
}
