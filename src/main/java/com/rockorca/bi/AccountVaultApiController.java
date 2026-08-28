package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/account-vault")
public class AccountVaultApiController {
  private final AccountVaultService vault;
  private final SessionService sessions;
  private final UserService users;
  private final AdpfluxService accountReports;
  private final ClickflareRevenueService revenueReports;

  public AccountVaultApiController(
      AccountVaultService vault,
      SessionService sessions,
      UserService users,
      AdpfluxService accountReports,
      ClickflareRevenueService revenueReports) {
    this.vault = vault;
    this.sessions = sessions;
    this.users = users;
    this.accountReports = accountReports;
    this.revenueReports = revenueReports;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    return vault.list(query, page, pageSize, actor.admin() ? null : actor.id());
  }

  @GetMapping("/operators")
  public Map<String, Object> operators(HttpServletRequest request) {
    UserRepository.UserAccount actor = admin(request);
    List<Map<String, Object>> operators = users.listUsers(actor).stream()
        .filter(user -> "user".equals(user.get("role")) && Boolean.TRUE.equals(user.get("active")))
        .toList();
    return ReportService.mapOf("operators", operators);
  }

  @PostMapping("/metrics")
  public Map<String, Object> metrics(
      @RequestBody Map<String, Object> payload, HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    Map<String, Object> report = accountReports.analyze(
        ReportService.text(payload.get("start")),
        ReportService.text(payload.get("end")), "", "all", false);
    if (actor.admin()) return report;
    Set<String> allowed = vault.ownedAccountIds(actor.id());
    return ReportService.mapOf("by_account", rows(report.get("by_account")).stream()
        .filter(row -> allowed.contains(ReportService.text(row.get("advertiserId"))))
        .toList());
  }

  @GetMapping("/revenue")
  public Map<String, Object> revenue(
      @RequestParam(defaultValue = "") String date,
      @RequestParam(defaultValue = "") String start,
      @RequestParam(defaultValue = "") String end,
      @RequestParam(defaultValue = "false") boolean refresh,
      @RequestParam(defaultValue = "false") boolean available,
      HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    Map<String, Object> report;
    if (!start.isBlank() || !end.isBlank()) {
      if (start.isBlank() || end.isBlank()) {
        throw new IllegalArgumentException("收益范围查询需要同时填写开始日期和结束日期");
      }
      report = revenueReports.revenueRange(start, end);
    } else {
      String resolved = date.isBlank()
          ? LocalDate.now(ReportService.BEIJING).toString() : date;
      // Credentials stay on the server, so an authenticated operator can safely request a
      // current snapshot while choosing an activity. Row visibility is still enforced below.
      report = revenueReports.revenue(resolved, refresh);
    }
    // Operators need the current activity list once while editing so they can establish the
    // first binding. Regular table requests remain restricted to activities bound to their data.
    if (actor.admin() || available) return report;
    Set<String> allowed = vault.ownedRevenueSourceIds(actor.id());
    Map<String, Object> filtered = new LinkedHashMap<>(report);
    filtered.put("rows", rows(report.get("rows")).stream()
        .filter(row -> allowed.contains(ReportService.text(row.get("campaignId"))))
        .toList());
    return filtered;
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    return ReportService.mapOf("ok", true, "entry", vault.create(payload, actor.id()));
  }

  @PutMapping("/{id}")
  public Map<String, Object> update(
      @PathVariable long id, @RequestBody Map<String, Object> payload, HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    if (!actor.admin() && !vault.ownedBy(id, actor.id())) forbidden();
    return ReportService.mapOf("ok", true, "entry", vault.update(id, payload, actor.id()));
  }

  @DeleteMapping("/{id}")
  public Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    if (!actor.admin() && !vault.ownedBy(id, actor.id())) forbidden();
    vault.delete(id);
    return ReportService.mapOf("ok", true);
  }

  @GetMapping("/options")
  public Map<String, Object> options(HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    return vault.options(actor.admin() ? null : actor.id());
  }

  @PostMapping("/options")
  public Map<String, Object> createOption(
      @RequestBody Map<String, Object> payload, HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    return ReportService.mapOf("ok", true, "option", vault.createOption(payload, actor.id()));
  }

  @DeleteMapping("/options/{id}")
  public Map<String, Object> deleteOption(@PathVariable long id, HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    if (!actor.admin() && !vault.optionOwnedBy(id, actor.id())) forbidden();
    vault.deleteOption(id);
    return ReportService.mapOf("ok", true);
  }

  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> importWorkbook(
      @RequestPart("file") MultipartFile file, HttpServletRequest request) throws Exception {
    UserRepository.UserAccount actor = currentUser(request);
    int imported = vault.importWorkbook(file.getBytes(), actor.id());
    return ReportService.mapOf("ok", true, "imported", imported);
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> export(HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    byte[] content = vault.exportWorkbook(actor.admin() ? null : actor.id());
    String filename = "关键词账户映射_"
        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .contentLength(content.length)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(filename, StandardCharsets.UTF_8).build().toString())
        .body(content);
  }

  private UserRepository.UserAccount admin(HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    users.requireAdmin(actor);
    return actor;
  }

  private UserRepository.UserAccount currentUser(HttpServletRequest request) {
    UserRepository.UserAccount actor = sessions.currentUser(request);
    if (actor == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return actor;
  }

  private static void forbidden() {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能修改自己创建的对应关系");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().filter(Map.class::isInstance)
        .map(item -> (Map<String, Object>) item).toList();
  }
}
