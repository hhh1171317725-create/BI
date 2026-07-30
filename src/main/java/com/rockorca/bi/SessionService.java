package com.rockorca.bi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
  /*
   * 登录 Cookie 是自包含的 HMAC 会话，不在服务端保存会话表。
   * 校验同时检查签名、用户状态和会话版本；修改密码或停用账号会使旧 Cookie 失效。
   */
  public static final String COOKIE_NAME = "report_session";
  public static final Duration LIFETIME = Duration.ofDays(36500);
  private static final String REQUEST_USER_ATTRIBUTE =
      SessionService.class.getName() + ".currentUser";

  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final UserService users;

  public SessionService(RuntimeConfig config, ObjectMapper objectMapper, UserService users) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.users = users;
  }

  public UserRepository.UserAccount authenticate(String username, String password) {
    return users.authenticate(username, password);
  }

  public String createToken(UserRepository.UserAccount user, long now) {
    try {
      Map<String, Object> session = new LinkedHashMap<>();
      session.put("userId", user.id());
      session.put("username", user.username());
      session.put("sessionVersion", user.sessionVersion());
      String payload = base64Url(objectMapper.writeValueAsBytes(session));
      return payload + "." + sign(payload);
    } catch (Exception error) {
      throw new IllegalStateException("创建登录会话失败", error);
    }
  }

  public boolean verifyToken(String token, long now) {
    return resolveToken(token, now) != null;
  }

  public UserRepository.UserAccount currentUser(HttpServletRequest request) {
    Object cached = request.getAttribute(REQUEST_USER_ATTRIBUTE);
    if (cached instanceof UserRepository.UserAccount user) return user;
    if (request.getCookies() == null) return null;
    for (Cookie cookie : request.getCookies()) {
      if (!COOKIE_NAME.equals(cookie.getName())) continue;
      UserRepository.UserAccount user = resolveToken(cookie.getValue(), System.currentTimeMillis());
      if (user != null) request.setAttribute(REQUEST_USER_ATTRIBUTE, user);
      return user;
    }
    return null;
  }

  private UserRepository.UserAccount resolveToken(String token, long now) {
    if (token == null) return null;
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) return null;
    if (!safeEqual(parts[1], sign(parts[0]))) return null;
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(parts[0]);
      Map<String, Object> session = objectMapper.readValue(decoded, new TypeReference<>() {});
      if (session.containsKey("expiresAt") && number(session.get("expiresAt")) <= now) return null;
      UserRepository.UserAccount user = session.containsKey("userId")
          ? users.findById(number(session.get("userId")))
          : users.findByUsername(String.valueOf(session.get("username")));
      if (user == null || !user.active()) return null;
      if (!safeEqual(user.username(), String.valueOf(session.get("username")))) return null;
      int tokenVersion = session.containsKey("sessionVersion")
          ? (int) number(session.get("sessionVersion"))
          : 1;
      return user.sessionVersion() == tokenVersion ? user : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  public boolean authenticated(HttpServletRequest request) {
    return currentUser(request) != null;
  }

  public ResponseCookie cookie(HttpServletRequest request, String token, Duration maxAge) {
    // 生产环境位于 Nginx 后方，必须透传 X-Forwarded-Proto 才能正确设置 Secure。
    boolean secure = request.isSecure() || "https".equalsIgnoreCase(firstHeader(request, "X-Forwarded-Proto"));
    return ResponseCookie.from(COOKIE_NAME, token)
        .path("/")
        .httpOnly(true)
        .sameSite("Lax")
        .secure(secure)
        .maxAge(maxAge)
        .build();
  }

  private String sign(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(
          config.get("REPORT_SESSION_SECRET", "").getBytes(StandardCharsets.UTF_8),
          "HmacSHA256"));
      return base64Url(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("签名登录会话失败", error);
    }
  }

  private static String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static boolean safeEqual(String left, String right) {
    return MessageDigest.isEqual(
        String.valueOf(left).getBytes(StandardCharsets.UTF_8),
        String.valueOf(right).getBytes(StandardCharsets.UTF_8));
  }

  private static long number(Object value) {
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static String firstHeader(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    return value == null ? "" : value.split(",")[0].trim();
  }
}
