package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

@Component
public class ToolPermissionInterceptor implements HandlerInterceptor {
  private static final Map<String, String> API_TO_TOOL = apiToTool();
  private final SessionService sessions;
  private final UserService users;
  private final ObjectMapper objectMapper;

  public ToolPermissionInterceptor(
      SessionService sessions, UserService users, ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.users = users;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String toolKey = toolFor(request.getRequestURI());
    if (toolKey == null) return true;
    UserRepository.UserAccount actor = sessions.currentUser(request);
    if (actor == null || users.canUseTool(actor, toolKey)) return true;
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getOutputStream(), Map.of("error", "管理员未授权使用该工具"));
    return false;
  }

  private static String toolFor(String path) {
    for (Map.Entry<String, String> entry : API_TO_TOOL.entrySet()) {
      if (path.equals(entry.getKey()) || path.startsWith(entry.getKey() + "/")) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static Map<String, String> apiToTool() {
    Map<String, String> result = new LinkedHashMap<>();
    result.put("/api/todos", "todo");
    result.put("/api/terminal", "terminal");
    result.put("/api/account-vault", "accountVault");
    result.put("/api/mail-dingtalk", "mailDingtalk");
    result.put("/api/chat", "chat");
    result.put("/api/jd-deeplink", "deeplink");
    result.put("/api/jd-account-deeplink", "deeplinkAccount");
    result.put("/api/jd-images", "jdImages");
    return result;
  }
}
