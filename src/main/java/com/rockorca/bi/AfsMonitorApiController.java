package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/afs-monitor")
public class AfsMonitorApiController {
  private final AfsMonitorService monitor;
  private final SessionService sessions;
  private final UserService users;

  public AfsMonitorApiController(
      AfsMonitorService monitor, SessionService sessions, UserService users) {
    this.monitor = monitor;
    this.sessions = sessions;
    this.users = users;
  }

  @GetMapping("/style")
  public ResponseEntity<Map<String, Object>> style(
      @RequestParam String channelId,
      @RequestParam String styleId,
      @RequestParam String start,
      @RequestParam String end,
      HttpServletRequest request) {
    admin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(monitor.query(channelId, styleId, start, end));
  }

  @GetMapping("/settings")
  public ResponseEntity<Map<String, Object>> settings(HttpServletRequest request) {
    admin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(monitor.credentialStatus());
  }

  @PostMapping("/settings")
  public ResponseEntity<Map<String, Object>> saveSettings(
      @RequestBody Map<String, Object> payload, HttpServletRequest request) {
    admin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(monitor.saveCookie(ReportService.text(payload.get("cookie"))));
  }

  @PostMapping("/sync")
  public ResponseEntity<Map<String, Object>> sync(HttpServletRequest request) {
    admin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(monitor.syncToday());
  }

  private void admin(HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
  }
}
