package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
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
  private final SessionService sessions;
  private final UserService users;

  public ReportApiController(
      ReportService reports,
      SessionService sessions,
      UserService users) {
    this.reports = reports;
    this.sessions = sessions;
    this.users = users;
  }

  @GetMapping("/current")
  public Map<String, Object> currentDhh() {
    return reports.currentDhh();
  }

  @GetMapping("/report-credentials")
  public ResponseEntity<Map<String, Object>> reportCredentials(HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(reports.savedReportCredentials());
  }

  @PostMapping("/report-credentials")
  public ResponseEntity<Map<String, Object>> saveReportCredentials(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(reports.saveReportCredentials(
            ReportService.text(payload.get("token")),
            ReportService.text(payload.get("userId"))));
  }

  @GetMapping("/report-visibility")
  public ResponseEntity<Map<String, Boolean>> reportVisibility(HttpServletRequest request) {
    UserRepository.UserAccount actor = sessions.currentUser(request);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(users.effectiveReportVisibility(actor, reports.reportVisibility()));
  }

  @PostMapping("/report-visibility")
  public ResponseEntity<Map<String, Object>> saveReportVisibility(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(reports.saveReportVisibility(
            !Boolean.FALSE.equals(payload.get("dhh")),
            !Boolean.FALSE.equals(payload.get("jd")),
            !Boolean.FALSE.equals(payload.get("jdLowActivity")),
            !Boolean.FALSE.equals(payload.get("adpflux"))));
  }

  @PostMapping("/load")
  public Map<String, Object> loadDhh(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
    return reports.loadDhh(
        ReportService.text(payload.get("token")),
        defaultUserId(payload.get("userId")),
        ReportService.text(payload.get("start")),
        ReportService.text(payload.get("end")));
  }

  @PostMapping("/analyze")
  public Map<String, Object> analyzeDhh(@RequestBody Map<String, Object> payload) {
    return reports.analyzeDhh(
        ReportService.text(payload.get("start")),
        ReportService.text(payload.get("end")),
        ReportService.text(payload.get("accountId")));
  }

  @GetMapping("/jd/current")
  public Map<String, Object> currentJd() {
    return reports.currentJd();
  }

  @PostMapping("/jd/load")
  public Map<String, Object> loadJd(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
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
        !Boolean.FALSE.equals(payload.get("excludeUnknownOptimizer")),
        ReportService.text(payload.get("accountId")));
  }

  private static String defaultUserId(Object value) {
    String userId = ReportService.text(value);
    return userId.isBlank() ? "20" : userId;
  }
}
