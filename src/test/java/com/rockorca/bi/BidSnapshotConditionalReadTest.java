package com.rockorca.bi;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BidSnapshotConditionalReadTest {
  @Test void unchangedSnapshotSkipsJsonDecodeAndNewVersionReadsMatchingPayloadInOneStatement()throws Exception{
    var reports=mock(ReportRepository.class);var connection=mock(Connection.class);var query=mock(PreparedStatement.class);var result=mock(ResultSet.class);
    var mapper=spy(new ObjectMapper());
    when(reports.openConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(query);when(query.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);when(result.getString("version")).thenReturn("7:v1");
    var snapshots=new BidSnapshotController(null,reports,mapper);ReflectionTestUtils.setField(snapshots,"initialized",true);
    var unchanged=snapshots.readOwnedSince(7,"7:v1");assertEquals("7:v1",unchanged.version());assertNull(unchanged.snapshot());verifyNoInteractions(mapper);
    var sql=ArgumentCaptor.forClass(String.class);verify(connection).prepareStatement(sql.capture());assertTrue(sql.getValue().contains("THEN NULL ELSE payload"));
    verify(query).setLong(1,7);verify(query).setLong(2,7);verify(query).setString(3,"7:v1");verify(query).setLong(4,7);
    when(result.getString("version")).thenReturn("7:v2");when(result.getString("snapshot_payload")).thenReturn("{\"updatedAt\":\"v2\",\"rows\":[{\"promotion_id\":\"123\"}]}");
    var changed=snapshots.readOwnedSince(7,"7:v1");assertEquals("7:v2",changed.version());assertEquals("v2",changed.snapshot().get("updatedAt"));
    when(result.next()).thenReturn(false);assertNull(snapshots.readOwnedSince(7,"7:").snapshot());assertEquals(Map.of(),snapshots.readOwnedSince(7,"").snapshot());
  }
}
