package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AccountApiController {
  private final SessionService sessions;
  private final UserService users;

  public AccountApiController(SessionService sessions, UserService users) {
    this.sessions = sessions;
    this.users = users;
  }

  @GetMapping("/account")
  public Map<String, Object> account(HttpServletRequest request) {
    UserRepository.UserAccount user = currentUser(request);
    return ReportService.mapOf("user", users.view(user, true));
  }

  @PostMapping("/account/password")
  public ResponseEntity<Map<String, Object>> changePassword(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    UserRepository.UserAccount updated = users.changeOwnPassword(
        currentUser(request),
        ReportService.text(payload.get("currentPassword")),
        ReportService.text(payload.get("newPassword")));
    String cookie = sessions.cookie(
        request,
        sessions.createToken(updated, System.currentTimeMillis()),
        SessionService.LIFETIME).toString();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie)
        .body(ReportService.mapOf("ok", true, "user", users.view(updated, true)));
  }

  @GetMapping("/users")
  public Map<String, Object> listUsers(HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    List<Map<String, Object>> result = users.listUsers(actor);
    return ReportService.mapOf("users", result);
  }

  @PostMapping("/users")
  public ResponseEntity<Map<String, Object>> createUser(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    Map<String, Object> created = users.createUser(
        currentUser(request),
        ReportService.text(payload.get("username")),
        ReportService.text(payload.get("password")),
        ReportService.text(payload.get("role")));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ReportService.mapOf("ok", true, "user", created));
  }

  @PostMapping("/users/{id}/reset-password")
  public Map<String, Object> resetPassword(
      @PathVariable long id,
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    return ReportService.mapOf(
        "ok", true,
        "user", users.resetPassword(
            currentUser(request), id, ReportService.text(payload.get("newPassword"))));
  }

  @PostMapping("/users/{id}/status")
  public Map<String, Object> setStatus(
      @PathVariable long id,
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    if (!(payload.get("active") instanceof Boolean active)) {
      throw new IllegalArgumentException("缺少有效的 active 状态");
    }
    return ReportService.mapOf(
        "ok", true,
        "user", users.setActive(currentUser(request), id, active));
  }

  @PostMapping("/users/{id}/report-visibility")
  public Map<String, Object> setReportVisibility(
      @PathVariable long id,
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    return ReportService.mapOf(
        "ok", true,
        "reportVisibility", users.saveReportVisibility(
            currentUser(request), id,
            !Boolean.FALSE.equals(payload.get("dhh")),
            !Boolean.FALSE.equals(payload.get("jd")),
            !Boolean.FALSE.equals(payload.get("jdLowActivity")),
            !Boolean.FALSE.equals(payload.get("adpflux"))));
  }

  @GetMapping("/tool-visibility")
  public Map<String, Boolean> toolVisibility(HttpServletRequest request) {
    return users.effectiveToolVisibility(currentUser(request));
  }

  @PostMapping("/users/{id}/tool-visibility")
  public Map<String, Object> setToolVisibility(
      @PathVariable long id,
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    return ReportService.mapOf(
        "ok", true,
        "toolVisibility", users.saveToolVisibility(currentUser(request), id, payload));
  }

  private UserRepository.UserAccount currentUser(HttpServletRequest request) {
    UserRepository.UserAccount user = sessions.currentUser(request);
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return user;
  }
}
