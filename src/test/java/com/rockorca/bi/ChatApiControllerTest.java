package com.rockorca.bi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class ChatApiControllerTest {
  @Test
  void administratorCanClearARoom() throws Exception {
    ChatService chat = mock(ChatService.class);
    SessionService sessions = mock(SessionService.class);
    UserService users = mock(UserService.class);
    UserRepository.UserAccount admin = account("admin");
    when(sessions.currentUser(any(HttpServletRequest.class))).thenReturn(admin);
    when(chat.clearRoom("公共聊天室"))
        .thenReturn(new ChatService.ClearResult("公共聊天室", 4, 2, 0));
    MockMvc mvc = mvc(chat, sessions, users);

    mvc.perform(delete("/api/chat/messages").param("room", "公共聊天室"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messagesDeleted").value(4))
        .andExpect(jsonPath("$.filesDeleted").value(2));

    verify(users).requireAdmin(admin);
    verify(chat).clearRoom("公共聊天室");
  }

  @Test
  void ordinaryUserCannotClearARoom() throws Exception {
    ChatService chat = mock(ChatService.class);
    SessionService sessions = mock(SessionService.class);
    UserService users = mock(UserService.class);
    UserRepository.UserAccount user = account("user");
    when(sessions.currentUser(any(HttpServletRequest.class))).thenReturn(user);
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可以执行此操作"))
        .when(users).requireAdmin(user);
    MockMvc mvc = mvc(chat, sessions, users);

    mvc.perform(delete("/api/chat/messages").param("room", "公共聊天室"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("仅管理员可以执行此操作"));
  }

  private static MockMvc mvc(ChatService chat, SessionService sessions, UserService users) {
    return MockMvcBuilders.standaloneSetup(new ChatApiController(chat, sessions, users))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private static UserRepository.UserAccount account(String role) {
    LocalDateTime now = LocalDateTime.of(2026, 8, 3, 18, 0);
    return new UserRepository.UserAccount(1L, "hhh", "hash", role, true, 1, now, now, now);
  }
}
