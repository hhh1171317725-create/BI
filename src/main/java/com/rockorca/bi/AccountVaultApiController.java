package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
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

  public AccountVaultApiController(AccountVaultService vault, SessionService sessions, UserService users) {
    this.vault = vault;
    this.sessions = sessions;
    this.users = users;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest request) {
    admin(request);
    return vault.list(query, page, pageSize);
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
    UserRepository.UserAccount actor = admin(request);
    return ReportService.mapOf("ok", true, "entry", vault.create(payload, actor.id()));
  }

  @PutMapping("/{id}")
  public Map<String, Object> update(
      @PathVariable long id, @RequestBody Map<String, Object> payload, HttpServletRequest request) {
    UserRepository.UserAccount actor = admin(request);
    return ReportService.mapOf("ok", true, "entry", vault.update(id, payload, actor.id()));
  }

  @DeleteMapping("/{id}")
  public Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
    admin(request);
    vault.delete(id);
    return ReportService.mapOf("ok", true);
  }

  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> importWorkbook(
      @RequestPart("file") MultipartFile file, HttpServletRequest request) throws Exception {
    UserRepository.UserAccount actor = admin(request);
    int imported = vault.importWorkbook(file.getBytes(), actor.id());
    return ReportService.mapOf("ok", true, "imported", imported);
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> export(HttpServletRequest request) {
    admin(request);
    byte[] content = vault.exportWorkbook();
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
    UserRepository.UserAccount actor = sessions.currentUser(request);
    if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    users.requireAdmin(actor);
    return actor;
  }
}
