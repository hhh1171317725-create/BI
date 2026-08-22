package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clickflare")
public class ClickflareApiController {
  private final ClickflareRevenueService revenue;
  private final SessionService sessions;
  private final UserService users;

  public ClickflareApiController(
      ClickflareRevenueService revenue, SessionService sessions, UserService users) {
    this.revenue = revenue;
    this.sessions = sessions;
    this.users = users;
  }

  @GetMapping("/settings")
  public ResponseEntity<Map<String, Object>> settings(HttpServletRequest request) {
    admin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(revenue.credentialStatus());
  }

  @PostMapping("/settings")
  public ResponseEntity<Map<String, Object>> saveSettings(
      @RequestBody Map<String, Object> payload, HttpServletRequest request) {
    admin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(revenue.saveCredentials(
        ReportService.text(payload.get("token")),
        ReportService.text(payload.get("companyId")),
        ReportService.text(payload.get("apiKeyId"))));
  }

  @GetMapping("/revenue")
  public ResponseEntity<Map<String, Object>> revenue(
      @RequestParam(defaultValue = "") String date,
      @RequestParam(defaultValue = "") String start,
      @RequestParam(defaultValue = "") String end,
      @RequestParam(defaultValue = "false") boolean refresh,
      HttpServletRequest request) {
    admin(request);
    if (!start.isBlank() || !end.isBlank()) {
      if (start.isBlank() || end.isBlank()) {
        throw new IllegalArgumentException("收益范围查询需要同时填写开始日期和结束日期");
      }
      return ResponseEntity.ok().cacheControl(CacheControl.noStore())
          .body(revenue.revenueRange(start, end));
    }
    String resolvedDate = date.isBlank() ? LocalDate.now(ReportService.BEIJING).toString() : date;
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(revenue.revenue(resolvedDate, refresh));
  }

  private void admin(HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
  }
}
