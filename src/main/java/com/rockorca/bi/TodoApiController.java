package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/todos")
public class TodoApiController {
  private final TodoService todos;
  private final SessionService sessions;

  public TodoApiController(TodoService todos, SessionService sessions) {
    this.todos = todos;
    this.sessions = sessions;
  }

  @GetMapping
  public Map<String, Object> list(HttpServletRequest request) {
    return ReportService.mapOf("items", todos.list(currentUser(request).id()));
  }

  @PostMapping
  public Map<String, Object> create(
      @RequestBody Map<String, Object> payload, HttpServletRequest request) {
    return ReportService.mapOf("item", todos.create(currentUser(request).id(), payload));
  }

  @PutMapping("/{id}")
  public Map<String, Object> update(
      @PathVariable long id,
      @RequestBody Map<String, Object> payload,
      HttpServletRequest request) {
    return ReportService.mapOf("item", todos.update(currentUser(request).id(), id, payload));
  }

  @DeleteMapping("/{id}")
  public Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
    todos.delete(currentUser(request).id(), id);
    return ReportService.mapOf("ok", true);
  }

  private UserRepository.UserAccount currentUser(HttpServletRequest request) {
    UserRepository.UserAccount user = sessions.currentUser(request);
    if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    return user;
  }
}
