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
@RequestMapping("/api/terminal")
public class TerminalApiController {
  private final TerminalSshService terminal;
  private final SessionService sessions;
  private final UserService users;

  public TerminalApiController(TerminalSshService terminal, SessionService sessions, UserService users) {
    this.terminal = terminal;
    this.sessions = sessions;
    this.users = users;
  }

  @GetMapping("/settings")
  public ResponseEntity<Map<String, Object>> settings(HttpServletRequest request) {
    requireAdmin(request);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(terminal.publicSettings());
  }

  @PostMapping("/settings")
  public Map<String, Object> saveSettings(
      @RequestBody Map<String, Object> payload, HttpServletRequest request) {
    requireAdmin(request);
    return terminal.saveSettings(payload);
  }

  @PostMapping("/test")
  public Map<String, Object> test(HttpServletRequest request) {
    requireAdmin(request);
    return terminal.testConnection();
  }

  private void requireAdmin(HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
  }
}
