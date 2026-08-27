package com.rockorca.bi;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
  private final UserRepository users;
  private final PasswordHasher passwords;
  private final RuntimeConfig config;
  private volatile boolean initialized;

  public UserService(UserRepository users, PasswordHasher passwords, RuntimeConfig config) {
    this.users = users;
    this.passwords = passwords;
    this.config = config;
  }

  public synchronized void initialize() {
    if (initialized) return;
    String username = normalizeUsername(config.get("REPORT_USERNAME", "hhh"));
    users.initialize(username, passwords.hash(config.get("REPORT_PASSWORD", "123456")));
    initialized = true;
  }

  public UserRepository.UserAccount authenticate(String username, String password) {
    initialize();
    String normalized = String.valueOf(username).trim();
    UserRepository.UserAccount user = users.findByUsername(normalized).orElse(null);
    if (user == null || !user.active() || !passwords.matches(password, user.passwordHash())) {
      return null;
    }
    users.markLogin(user.id());
    return users.findById(user.id()).orElse(user);
  }

  public UserRepository.UserAccount findById(long id) {
    initialize();
    return users.findById(id).orElse(null);
  }

  public UserRepository.UserAccount findByUsername(String username) {
    initialize();
    return users.findByUsername(String.valueOf(username).trim()).orElse(null);
  }

  public UserRepository.UserAccount changeOwnPassword(
      UserRepository.UserAccount actor,
      String currentPassword,
      String newPassword) {
    UserRepository.UserAccount current = requireExisting(actor.id());
    if (!passwords.matches(currentPassword, current.passwordHash())) {
      throw new IllegalArgumentException("当前密码不正确");
    }
    validatePassword(newPassword);
    if (passwords.matches(newPassword, current.passwordHash())) {
      throw new IllegalArgumentException("新密码不能与当前密码相同");
    }
    return users.updatePassword(current.id(), passwords.hash(newPassword));
  }

  public List<Map<String, Object>> listUsers(UserRepository.UserAccount actor) {
    requireAdmin(actor);
    initialize();
    return users.list().stream().map(user -> view(user, user.id() == actor.id())).toList();
  }

  public Map<String, Object> createUser(
      UserRepository.UserAccount actor,
      String username,
      String password,
      String role) {
    requireAdmin(actor);
    initialize();
    String normalizedUsername = normalizeUsername(username);
    validatePassword(password);
    String normalizedRole = normalizeRole(role);
    return view(users.create(normalizedUsername, passwords.hash(password), normalizedRole), false);
  }

  public Map<String, Object> resetPassword(
      UserRepository.UserAccount actor,
      long targetId,
      String newPassword) {
    requireAdmin(actor);
    if (actor.id() == targetId) {
      throw new IllegalArgumentException("请在“修改我的密码”中修改当前账号密码");
    }
    validatePassword(newPassword);
    requireExisting(targetId);
    return view(users.updatePassword(targetId, passwords.hash(newPassword)), false);
  }

  public Map<String, Object> setActive(
      UserRepository.UserAccount actor,
      long targetId,
      boolean active) {
    requireAdmin(actor);
    if (actor.id() == targetId) throw new IllegalArgumentException("不能停用当前登录账号");
    requireExisting(targetId);
    return view(users.setActive(targetId, active), false);
  }

  public Map<String, Boolean> effectiveReportVisibility(
      UserRepository.UserAccount actor, Map<String, Object> globalVisibility) {
    initialize();
    if (actor == null || !actor.active()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    Map<String, Boolean> result = new LinkedHashMap<>();
    Map<String, Boolean> personal = actor.admin()
        ? Map.of("dhh", true, "jd", true, "jdLowActivity", true, "adpflux", true)
        : users.reportVisibility(actor.id());
    for (String key : List.of("dhh", "jd", "jdLowActivity", "adpflux")) {
      result.put(key, !Boolean.FALSE.equals(globalVisibility.get(key))
          && !Boolean.FALSE.equals(personal.get(key)));
    }
    return result;
  }

  public Map<String, Boolean> saveReportVisibility(
      UserRepository.UserAccount actor,
      long targetId,
      boolean dhh,
      boolean jd,
      boolean jdLowActivity,
      boolean adpflux) {
    requireAdmin(actor);
    initialize();
    UserRepository.UserAccount target = requireExisting(targetId);
    if (target.admin()) {
      throw new IllegalArgumentException("管理员始终可以访问已启用的日报，无需单独设置");
    }
    return users.saveReportVisibility(targetId, dhh, jd, jdLowActivity, adpflux);
  }

  public Map<String, Object> view(UserRepository.UserAccount user, boolean current) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", user.id());
    result.put("username", user.username());
    result.put("role", user.role());
    result.put("active", user.active());
    result.put("current", current);
    result.put("createdAt", text(user.createdAt()));
    result.put("updatedAt", text(user.updatedAt()));
    result.put("lastLoginAt", text(user.lastLoginAt()));
    result.put("reportVisibility", user.admin()
        ? Map.of("dhh", true, "jd", true, "jdLowActivity", true, "adpflux", true)
        : users.reportVisibility(user.id()));
    return result;
  }

  public void requireAdmin(UserRepository.UserAccount user) {
    if (user == null || !user.active() || !user.admin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可以执行此操作");
    }
  }

  private UserRepository.UserAccount requireExisting(long id) {
    initialize();
    UserRepository.UserAccount user = users.findById(id).orElse(null);
    if (user == null) throw new IllegalArgumentException("用户不存在");
    return user;
  }

  private static String normalizeUsername(String username) {
    String value = String.valueOf(username).trim();
    if (!value.matches("^[A-Za-z0-9_.-]{3,50}$")) {
      throw new IllegalArgumentException("用户名需为 3-50 位字母、数字、点、下划线或短横线");
    }
    return value;
  }

  private static String normalizeRole(String role) {
    String value = String.valueOf(role).trim().toLowerCase();
    if (!value.equals("admin") && !value.equals("user")) {
      throw new IllegalArgumentException("用户角色无效");
    }
    return value;
  }

  private static void validatePassword(String password) {
    int length = String.valueOf(password).length();
    if (length < 6 || length > 128) {
      throw new IllegalArgumentException("密码长度需为 6-128 位");
    }
  }

  private static String text(LocalDateTime value) {
    return value == null ? "" : value.toString();
  }
}
