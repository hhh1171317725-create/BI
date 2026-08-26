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
@RequestMapping("/api/mail-dingtalk")
public class MailDingtalkApiController {
  private final MailDingtalkService mail;
  private final SessionService sessions;
  private final UserService users;

  public MailDingtalkApiController(
      MailDingtalkService mail, SessionService sessions, UserService users) {
    this.mail = mail;
    this.sessions = sessions;
    this.users = users;
  }

  @GetMapping("/settings")
  public ResponseEntity<Map<String, Object>> settings(HttpServletRequest request) {
    admin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mail.status());
  }

  @PostMapping("/settings")
  public ResponseEntity<Map<String, Object>> settings(
      @RequestBody Map<String, Object> payload, HttpServletRequest request) {
    admin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mail.saveSettings(
        ReportService.text(payload.get("email")),
        ReportService.text(payload.get("authorizationCode")),
        ReportService.text(payload.get("webhook")),
        ReportService.text(payload.get("secret")),
        ReportService.text(payload.get("keyword")),
        Boolean.TRUE.equals(payload.get("autoEnabled"))));
  }

  @PostMapping("/forward")
  public ResponseEntity<Map<String, Object>> forward(HttpServletRequest request) {
    admin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mail.forwardNow());
  }

  @PostMapping("/test-latest")
  public ResponseEntity<Map<String, Object>> testLatest(HttpServletRequest request) {
    admin(request);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mail.testLatest());
  }

  private void admin(HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
  }
}
