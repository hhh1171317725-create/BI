package com.rockorca.bi;

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

  public TerminalApiController(TerminalSshService terminal) {
    this.terminal = terminal;
  }

  @GetMapping("/settings")
  public ResponseEntity<Map<String, Object>> settings() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(terminal.publicSettings());
  }

  @PostMapping("/settings")
  public Map<String, Object> saveSettings(@RequestBody Map<String, Object> payload) {
    return terminal.saveSettings(payload);
  }

  @PostMapping("/test")
  public Map<String, Object> test() {
    return terminal.testConnection();
  }
}
