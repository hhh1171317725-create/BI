package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录会话 API，只返回 JSON 和 Cookie，不负责页面跳转。 */
@RestController
@RequestMapping("/api")
public class AuthApiController {
  private final SessionService sessions;
  private final UserService users;

  public AuthApiController(SessionService sessions, UserService users) {
    this.sessions = sessions;
    this.users = users;
  }

  @GetMapping("/session")
  public Map<String, Object> session(HttpServletRequest request) {
    UserRepository.UserAccount user = sessions.currentUser(request);
    return ReportService.mapOf(
        "authenticated", user != null,
        "user", user == null ? Map.of() : users.view(user, true));
  }

  @PostMapping("/login")
  public ResponseEntity<Map<String, Object>> login(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    UserRepository.UserAccount user = sessions.authenticate(
        ReportService.text(payload.get("username")),
        ReportService.text(payload.get("password")));
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ReportService.mapOf("error", "用户名或密码错误"));
    }
    String cookie = sessions.cookie(
        request,
        sessions.createToken(user, System.currentTimeMillis()),
        SessionService.LIFETIME).toString();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie)
        .body(ReportService.mapOf(
            "ok", true,
            "redirect", "/",
            "user", users.view(user, true)));
  }

  @PostMapping("/logout")
  public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE,
            sessions.cookie(request, "", Duration.ZERO).toString())
        .body(ReportService.mapOf("ok", true));
  }
}
