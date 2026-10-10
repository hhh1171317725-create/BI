package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/pet/rules")
public class PetAnalysisRulesController {
  private final PetAnalysisRulesService rules;
  private final SessionService sessions;
  private final UserService users;

  public PetAnalysisRulesController(PetAnalysisRulesService rules, SessionService sessions, UserService users) {
    this.rules = rules;
    this.sessions = sessions;
    this.users = users;
  }

  @GetMapping
  public Map<String, Object> get(HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    return view(rules.snapshot(), actor.admin());
  }

  @PostMapping
  public Map<String, Object> save(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
    UserRepository.UserAccount actor = currentUser(request);
    users.requireAdmin(actor);
    PetAnalysisRulesService.fields(payload, Set.of("version", "rules"), "保存规则请求");
    long version = PetAnalysisRulesService.nonnegativeLong(payload.get("version"), "规则版本");
    return view(rules.save(version, PetAnalysisRulesService.rulesFrom(payload.get("rules"))), true);
  }

  private UserRepository.UserAccount currentUser(HttpServletRequest request) {
    UserRepository.UserAccount actor = sessions.currentUser(request);
    if (actor == null || !actor.active()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return actor;
  }

  private static Map<String, Object> view(PetAnalysisRulesService.Snapshot snapshot, boolean canManage) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", snapshot.version());
    result.put("updatedAt", snapshot.updatedAt());
    result.put("rules", snapshot.rules());
    result.put("canManage", canManage);
    return result;
  }
}
