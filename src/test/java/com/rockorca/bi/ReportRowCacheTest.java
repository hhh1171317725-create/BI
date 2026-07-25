package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReportRowCacheTest {
  @Test
  void loadsOnceUntilInvalidatedAndProtectsCachedRows() {
    ReportRowCache cache = new ReportRowCache();
    AtomicInteger loads = new AtomicInteger();

    var loader = (java.util.function.Supplier<List<Map<String, Object>>>) () -> {
      int version = loads.incrementAndGet();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("版本", version);
      return List.of(row);
    };

    assertEquals(1, cache.get(loader).getFirst().get("版本"));
    assertEquals(1, cache.get(loader).getFirst().get("版本"));
    assertEquals(1, loads.get());
    assertThrows(UnsupportedOperationException.class,
        () -> cache.get(loader).getFirst().put("版本", 99));

    cache.invalidate();

    assertEquals(2, cache.get(loader).getFirst().get("版本"));
    assertEquals(2, loads.get());
  }
}
