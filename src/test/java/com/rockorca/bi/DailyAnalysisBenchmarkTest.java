package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;

/** Opt-in local CPU/payload benchmark; deliberately excludes database and network latency. */
@EnabledIfSystemProperty(named = "report.benchmark", matches = "true")
class DailyAnalysisBenchmarkTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final ReportService service = new ReportService(null, null, null, mapper);

  static List<Map<String,Object>> rows(int count) {
    var rows = new ArrayList<Map<String,Object>>();
    for (int i = 0; i < count; i++) {
      var row = new LinkedHashMap<String,Object>();
      row.put("日期", "2026-09-%02d".formatted(1 + (i / 400) % 15));
      row.put("优化师", "优化师" + i % 40);
      row.put("项目", "项目" + i % 6);
      row.put("任务名", "任务" + i % 20);
      row.put("媒体", "媒体" + i % 2);
      row.put("媒体账户名称", "账户" + i % 400);
      row.put("媒体账户ID", String.valueOf(100000 + i % 400));
      row.put("推客用户名", "推客" + i % 80);
      for (String field : ReportService.DHH_NUMERIC_FIELDS) row.put(field, (i % 99 + 1) / 3d);
      for (String field : CsvImportService.JD_NUMERIC_FIELDS) row.put(field, (i % 99 + 1) / 3d);
      rows.add(row);
    }
    return rows;
  }

  @Test void measure() throws Exception {
    var rows = rows(6000);
    var queries = new LinkedHashMap<String,Supplier<Map<String,Object>>>();
    queries.put("dhh_optimizer", () -> service.buildDhhAnalysis(rows, "2026-09-01", "2026-09-15", "benchmark", "by_optimizer"));
    queries.put("jd_all", () -> service.buildJdAnalysis(rows, "2026-09-01", "2026-09-15", true, "benchmark"));
    queries.put("jd_optimizer", () -> service.buildJdAnalysis(rows, "2026-09-01", "2026-09-15", true, "benchmark", "by_optimizer"));
    var results = new LinkedHashMap<String,Object>();
    for (var query : queries.entrySet()) {
      for (int i = 0; i < 3; i++) mapper.writeValueAsBytes(query.getValue().get());
      long[] times = new long[5];
      byte[] json = null;
      Map<String,Object> report = null;
      for (int i = 0; i < times.length; i++) {
        long start = System.nanoTime();
        report = query.getValue().get();
        json = mapper.writeValueAsBytes(report);
        times[i] = System.nanoTime() - start;
      }
      Arrays.sort(times);
      report.remove("nextScheduledRefreshAt");
      String label = System.getProperty("report.benchmark.label", "current");
      Path output = Path.of(".runtime", "report-benchmark-" + label + "-" + query.getKey() + ".json");
      Files.createDirectories(output.getParent());
      Files.writeString(output, mapper.writeValueAsString(report));
      if (!label.equals("before")) {
        Path before = Path.of(".runtime", "report-benchmark-before-" + query.getKey() + ".json");
        if (Files.exists(before)) assertEquals(mapper.readTree(Files.readString(before)), mapper.valueToTree(report));
      }
      var metrics = Map.of("medianMs", times[2] / 1_000_000d, "jsonBytes", json.length);
      results.put(query.getKey(), metrics);
      System.out.println("REPORT_BENCHMARK " + label + " " + query.getKey() + " " + metrics);
    }
    Files.writeString(Path.of(".runtime", "report-benchmark-" + System.getProperty("report.benchmark.label", "current") + "-metrics.json"), mapper.writeValueAsString(results));
  }
}
