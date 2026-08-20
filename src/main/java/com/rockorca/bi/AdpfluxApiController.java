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

@RestController
@RequestMapping("/api/adpflux")
public class AdpfluxApiController {
  private final AdpfluxService reports;
  private final SessionService sessions;
  private final UserService users;

  public AdpfluxApiController(
      AdpfluxService reports,
      SessionService sessions,
      UserService users) {
    this.reports = reports;
    this.sessions = sessions;
    this.users = users;
  }

  @GetMapping("/current")
  public Map<String, Object> current() {
    return reports.current();
  }

  @PostMapping("/analyze")
  public Map<String, Object> analyze(@RequestBody Map<String, Object> payload) {
    return reports.analyze(
        ReportService.text(payload.get("start")),
        ReportService.text(payload.get("end")),
        ReportService.text(payload.get("query")),
        ReportService.text(payload.get("status")),
        Boolean.TRUE.equals(payload.get("spendingOnly")));
  }

  @GetMapping("/settings")
  public ResponseEntity<Map<String, Object>> settings(HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(reports.credentialStatus());
  }

  @PostMapping("/settings")
  public ResponseEntity<Map<String, Object>> saveSettings(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(reports.saveCredentials(
            ReportService.text(payload.get("cookie")),
            ReportService.text(payload.get("csrfToken")),
            ReportService.text(payload.get("orgId")),
            ReportService.text(payload.get("orgName")),
            ReportService.text(payload.get("currency")),
            ReportService.text(payload.get("timezone"))));
  }

  @PostMapping("/sync")
  public Map<String, Object> sync(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
    return reports.sync(
        ReportService.text(payload.get("start")),
        ReportService.text(payload.get("end")),
        ReportService.text(payload.get("cookie")),
        ReportService.text(payload.get("csrfToken")),
        ReportService.text(payload.get("orgId")),
        ReportService.text(payload.get("orgName")),
        ReportService.text(payload.get("currency")),
        ReportService.text(payload.get("timezone")));
  }
}
