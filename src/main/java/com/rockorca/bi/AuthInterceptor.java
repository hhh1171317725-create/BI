package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

/** 对后端 JSON API 执行登录校验。静态前端由 Nginx 独立托管。 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
  private final SessionService sessions;
  private final ObjectMapper objectMapper;

  public AuthInterceptor(SessionService sessions, ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String path = request.getRequestURI();
    if (path.equals("/api/login") || path.equals("/api/logout")) {
      return true;
    }
    if (sessions.authenticated(request)) {
      return true;
    }

    // API 永远返回 JSON；跳转登录页由前端收到 401 后处理。
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getOutputStream(), Map.of("error", "登录已失效，请重新登录"));
    return false;
  }
}
