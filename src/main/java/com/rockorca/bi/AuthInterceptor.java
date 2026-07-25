package com.rockorca.bi;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

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
    // 该白名单是登录边界；其他页面、脚本、样式和 API 均必须持有有效会话。
    if (path.equals("/login") || path.equals("/api/login") || path.equals("/api/logout")
        || path.equals("/assets/miku-pet.png")) {
      return true;
    }
    if (sessions.authenticated(request)) return true;
    if (path.startsWith("/api/")) {
      // API 返回 JSON 401，页面请求则 302 到登录页，避免 fetch 收到 HTML。
      response.setStatus(401);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      objectMapper.writeValue(response.getOutputStream(), Map.of("error", "登录已失效，请重新登录"));
    } else {
      response.sendRedirect("/login");
    }
    return false;
  }
}
