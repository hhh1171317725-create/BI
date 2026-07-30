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
   * 校验同时检查签名、当前用户名和过期时间；修改用户名或会话密钥会使旧 Cookie 失效。
   */
  public static final String COOKIE_NAME = "report_session";
  public static final Duration LIFETIME = Duration.ofDays(36500);

  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;

  public SessionService(RuntimeConfig config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
  }

  public boolean validateCredentials(String username, String password) {
    return safeEqual(username, config.get("REPORT_USERNAME", "hhh"))
        && safeEqual(password, config.get("REPORT_PASSWORD", "123456"));
  }

  public String createToken(long now) {
    try {
      Map<String, Object> session = new LinkedHashMap<>();
      session.put("username", config.get("REPORT_USERNAME", "hhh"));
      String payload = base64Url(objectMapper.writeValueAsBytes(session));
      return payload + "." + sign(payload);
    } catch (Exception error) {
      throw new IllegalStateException("创建登录会话失败", error);
    }
  }

  public boolean verifyToken(String token, long now) {
    if (token == null) return false;
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) return false;
    if (!safeEqual(parts[1], sign(parts[0]))) return false;
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(parts[0]);
      Map<String, Object> session = objectMapper.readValue(decoded, new TypeReference<>() {});
      if (!config.get("REPORT_USERNAME", "hhh").equals(String.valueOf(session.get("username")))) {
        return false;
      }
      // New sessions are permanent. Keep honoring expiry on legacy sessions issued before this change.
      return !session.containsKey("expiresAt") || number(session.get("expiresAt")) > now;
    } catch (Exception ignored) {
      return false;
    }
  }

  public boolean authenticated(HttpServletRequest request) {
    if (request.getCookies() == null) return false;
    for (Cookie cookie : request.getCookies()) {
      if (COOKIE_NAME.equals(cookie.getName())) return verifyToken(cookie.getValue(), System.currentTimeMillis());
    }
    return false;
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
