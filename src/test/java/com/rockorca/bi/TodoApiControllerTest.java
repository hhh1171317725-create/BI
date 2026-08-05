package com.rockorca.bi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TodoApiControllerTest {
  private TodoService todos;
  private SessionService sessions;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    todos = mock(TodoService.class);
    sessions = mock(SessionService.class);
    LocalDateTime now = LocalDateTime.of(2026, 8, 5, 10, 0);
    UserRepository.UserAccount user = new UserRepository.UserAccount(
        7L, "tester", "hash", "user", true, 1, now, now, now);
    when(sessions.currentUser(any(HttpServletRequest.class))).thenReturn(user);
    mvc = MockMvcBuilders.standaloneSetup(new TodoApiController(todos, sessions))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void listsOnlyCurrentUsersItems() throws Exception {
    TodoService.TodoItem item = item(false);
    when(todos.list(7L)).thenReturn(List.of(item));

    mvc.perform(get("/api/todos"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].title").value("检查账户数据"));

    verify(todos).list(7L);
  }

  @Test
  void createsUpdatesAndDeletesForCurrentUser() throws Exception {
    TodoService.TodoItem created = item(false);
    TodoService.TodoItem completed = item(true);
    when(todos.create(eq(7L), any())).thenReturn(created);
    when(todos.update(eq(7L), eq(12L), any())).thenReturn(completed);

    mvc.perform(post("/api/todos")
            .contentType("application/json")
            .content("{\"title\":\"检查账户数据\",\"priority\":\"high\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.item.id").value(12));

    mvc.perform(put("/api/todos/12")
            .contentType("application/json")
            .content("{\"completed\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.item.completed").value(true));

    mvc.perform(delete("/api/todos/12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));

    verify(todos).delete(7L, 12L);
  }

  private static TodoService.TodoItem item(boolean completed) {
    LocalDateTime now = LocalDateTime.of(2026, 8, 5, 10, 0);
    return new TodoService.TodoItem(
        12L, "检查账户数据", "high", LocalDate.of(2026, 8, 6), completed, now, now);
  }
}
