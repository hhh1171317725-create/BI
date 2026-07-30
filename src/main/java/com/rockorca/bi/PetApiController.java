package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 数据助手 API。模型凭据和底表访问始终留在后端。 */
@RestController
@RequestMapping("/api/pet")
public class PetApiController {
  private final PetService pets;
  private final SessionService sessions;
  private final UserService users;

  public PetApiController(PetService pets, SessionService sessions, UserService users) {
    this.pets = pets;
    this.sessions = sessions;
    this.users = users;
  }

  @PostMapping("/chat")
  public Map<String, Object> chat(@RequestBody Map<String, Object> payload) {
    return pets.chat(payload);
  }

  @GetMapping("/config")
  public Map<String, Object> config(HttpServletRequest request) {
    UserRepository.UserAccount user = sessions.currentUser(request);
    Map<String, Object> result = new LinkedHashMap<>(pets.aiConfigStatus());
    result.put("canManage", user != null && user.admin());
    return result;
  }

  @PostMapping("/config")
  public Map<String, Object> saveConfig(
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    users.requireAdmin(sessions.currentUser(request));
    Map<String, Object> result = new LinkedHashMap<>(pets.saveAiConfig(
        ReportService.text(payload.get("provider")),
        ReportService.text(payload.get("apiKey")),
        ReportService.text(payload.get("model"))));
    result.put("canManage", true);
    return result;
  }
}
