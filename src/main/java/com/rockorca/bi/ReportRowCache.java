package com.rockorca.bi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 报表底表的进程内缓存。
 *
 * <p>第一次读取时由调用方查询数据库；后续读取复用不可变快照。全量数据写入成功后，
 * Repository 会调用 {@link #invalidate()}，保证下一次读取重新查询数据库。</p>
 */
final class ReportRowCache {
  private volatile List<Map<String, Object>> rows;

  List<Map<String, Object>> get(Supplier<List<Map<String, Object>>> loader) {
    List<Map<String, Object>> current = rows;
    if (current != null) return current;
    synchronized (this) {
      if (rows == null) rows = immutableSnapshot(loader.get());
      return rows;
    }
  }

  synchronized void invalidate() {
    rows = null;
  }

  private static List<Map<String, Object>> immutableSnapshot(
      List<Map<String, Object>> source) {
    return source.stream()
        .map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row)))
        .toList();
  }
}
