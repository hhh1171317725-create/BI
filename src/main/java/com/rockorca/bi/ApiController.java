package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiController {
  private final SessionService sessions;
  private final ReportService reports;
  private final PetService pets;

  public ApiController(SessionService sessions, ReportService reports, PetService pets) {
    this.sessions = sessions;
    this.reports = reports;
    this.pets = pets;
  }

  @GetMapping("/login")
  public ResponseEntity<?> loginPage(HttpServletRequest request) {
    if (sessions.authenticated(request)) {
      return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/").build();
    }
    return file("login.html", MediaType.valueOf("text/html;charset=UTF-8"));
  }

  @GetMapping("/")
  public ResponseEntity<ClassPathResource> index() {
    return file("index.html", MediaType.valueOf("text/html;charset=UTF-8"));
  }

  @GetMapping({"/jd", "/jd.html"})
  public ResponseEntity<ClassPathResource> jd() {
    return file("jd.html", MediaType.valueOf("text/html;charset=UTF-8"));
  }

  @GetMapping("/echarts.min.js")
  public ResponseEntity<ClassPathResource> echarts() {
    return file("echarts.min.js", MediaType.valueOf("application/javascript;charset=UTF-8"));
  }

  @GetMapping("/pet.js")
  public ResponseEntity<ClassPathResource> petScript() {
    return file("pet.js", MediaType.valueOf("application/javascript;charset=UTF-8"));
  }

  @GetMapping("/pet.css")
  public ResponseEntity<ClassPathResource> petStyles() {
    return file("pet.css", MediaType.valueOf("text/css;charset=UTF-8"));
  }

  @GetMapping("/assets/miku-pet.png")
  public ResponseEntity<ClassPathResource> petImage() {
    return file("assets/miku-pet.png", MediaType.IMAGE_PNG);
  }

  @PostMapping("/api/login")
  public ResponseEntity<Map<String, Object>> login(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    if (!sessions.validateCredentials(
        ReportService.text(payload.get("username")),
        ReportService.text(payload.get("password")))) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ReportService.mapOf("error", "用户名或密码错误"));
    }
    String cookie = sessions.cookie(
        request, sessions.createToken(System.currentTimeMillis()), SessionService.LIFETIME).toString();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie)
        .body(ReportService.mapOf("ok", true, "redirect", "/"));
  }

  @PostMapping("/api/logout")
  public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE,
            sessions.cookie(request, "", Duration.ZERO).toString())
        .body(ReportService.mapOf("ok", true));
  }

  @GetMapping("/api/current")
  public Map<String, Object> currentDhh() {
    return reports.currentDhh();
  }

  @PostMapping("/api/load")
  public Map<String, Object> loadDhh(@RequestBody Map<String, Object> payload) {
    return reports.loadDhh(
        ReportService.text(payload.get("token")),
        defaultUserId(payload.get("userId")));
  }

  @PostMapping("/api/analyze")
  public Map<String, Object> analyzeDhh(@RequestBody Map<String, Object> payload) {
    return reports.analyzeDhh(
        ReportService.text(payload.get("start")),
        ReportService.text(payload.get("end")));
  }

  @GetMapping("/api/jd/current")
  public Map<String, Object> currentJd() {
    return reports.currentJd();
  }

  @PostMapping("/api/jd/load")
  public Map<String, Object> loadJd(@RequestBody Map<String, Object> payload) {
    return reports.loadJd(
        ReportService.text(payload.get("token")),
        defaultUserId(payload.get("userId")),
        !Boolean.FALSE.equals(payload.get("excludeUnknownOptimizer")));
  }

  @PostMapping("/api/jd/analyze")
  public Map<String, Object> analyzeJd(@RequestBody Map<String, Object> payload) {
    return reports.analyzeJd(
        ReportService.text(payload.get("start")),
        ReportService.text(payload.get("end")),
        !Boolean.FALSE.equals(payload.get("excludeUnknownOptimizer")));
  }

  @PostMapping("/api/pet/chat")
  public Map<String, Object> petChat(@RequestBody Map<String, Object> payload) {
    return pets.chat(payload);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleError(Exception error) {
    Throwable source = error.getCause() == null ? error : error.getCause();
    String message = source.getMessage();
    if (message == null || message.isBlank()) message = "服务异常";
    return ResponseEntity.badRequest().body(ReportService.mapOf("error", message));
  }

  private static String defaultUserId(Object value) {
    String userId = ReportService.text(value);
    return userId.isBlank() ? "20" : userId;
  }

  private static ResponseEntity<ClassPathResource> file(String filename, MediaType contentType) {
    try {
      ClassPathResource target = new ClassPathResource("static/" + filename);
      if (!target.exists()) {
        return ResponseEntity.notFound().build();
      }
      return ResponseEntity.ok()
          .contentType(contentType)
          .contentLength(target.contentLength())
          .cacheControl(CacheControl.noCache())
          .body(target);
    } catch (Exception error) {
      throw new IllegalStateException("读取页面文件失败：" + error.getMessage(), error);
    }
  }
}
