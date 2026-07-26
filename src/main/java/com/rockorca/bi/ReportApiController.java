package com.rockorca.bi;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 大航海和京东报表 API，只向前端提供 JSON 数据。 */
@RestController
@RequestMapping("/api")
public class ReportApiController {
  private final ReportService reports;

  public ReportApiController(ReportService reports) {
    this.reports = reports;
  }

  @GetMapping("/current")
  public Map<String, Object> currentDhh() {
    return reports.currentDhh();
  }

  @PostMapping("/load")
  public Map<String, Object> loadDhh(@RequestBody Map<String, Object> payload) {
    return reports.loadDhh(
        ReportService.text(payload.get("token")),
        defaultUserId(payload.get("userId")));
  }

  @PostMapping("/analyze")
  public Map<String, Object> analyzeDhh(@RequestBody Map<String, Object> payload) {
    return reports.analyzeDhh(
        ReportService.text(payload.get("start")),
        ReportService.text(payload.get("end")));
  }

  @GetMapping("/jd/current")
  public Map<String, Object> currentJd() {
    return reports.currentJd();
  }

  @PostMapping("/jd/load")
  public Map<String, Object> loadJd(@RequestBody Map<String, Object> payload) {
    return reports.loadJd(
        ReportService.text(payload.get("token")),
        defaultUserId(payload.get("userId")),
        !Boolean.FALSE.equals(payload.get("excludeUnknownOptimizer")));
  }

  @PostMapping("/jd/analyze")
  public Map<String, Object> analyzeJd(@RequestBody Map<String, Object> payload) {
    return reports.analyzeJd(
        ReportService.text(payload.get("start")),
        ReportService.text(payload.get("end")),
        !Boolean.FALSE.equals(payload.get("excludeUnknownOptimizer")));
  }

  private static String defaultUserId(Object value) {
    String userId = ReportService.text(value);
    return userId.isBlank() ? "20" : userId;
  }
}
