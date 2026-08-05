package com.rockorca.bi;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Stores personal Todo items in MySQL and enforces ownership on every operation. */
@Service
public class TodoService {
  private final ReportRepository reports;

  public TodoService(ReportRepository reports) {
    this.reports = reports;
  }

  public void initialize() {
    try (Connection connection = reports.openConnection();
         Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS `todo_items` (
            `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
            `user_id` BIGINT UNSIGNED NOT NULL,
            `title` VARCHAR(240) NOT NULL,
            `priority` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'normal',
            `due_date` DATE NULL,
            `completed` TINYINT(1) NOT NULL DEFAULT 0,
            `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
            `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3),
            PRIMARY KEY (`id`),
            KEY `idx_todo_user_state` (`user_id`, `completed`, `due_date`, `updated_at`)
          ) ENGINE=InnoDB COMMENT='Personal Todo items'
          """);
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public List<TodoItem> list(long userId) {
    List<TodoItem> items = new ArrayList<>();
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT id, title, priority, due_date, completed, created_at, updated_at
             FROM todo_items
             WHERE user_id = ?
             ORDER BY completed ASC, due_date IS NULL ASC, due_date ASC, updated_at DESC
             """)) {
      statement.setLong(1, userId);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) items.add(map(result));
      }
      return items;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public TodoItem create(long userId, Map<String, Object> payload) {
    String title = title(payload.get("title"));
    String priority = priority(payload.get("priority"));
    LocalDate dueDate = date(payload.get("dueDate"));
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             INSERT INTO todo_items (user_id, title, priority, due_date, completed)
             VALUES (?, ?, ?, ?, 0)
             """, Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, userId);
      statement.setString(2, title);
      statement.setString(3, priority);
      setDate(statement, 4, dueDate);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        if (!keys.next()) throw new IllegalStateException("创建任务后未返回任务 ID");
        return find(userId, keys.getLong(1));
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public TodoItem update(long userId, long id, Map<String, Object> payload) {
    TodoItem current = find(userId, id);
    String title = payload.containsKey("title") ? title(payload.get("title")) : current.title();
    String priority = payload.containsKey("priority")
        ? priority(payload.get("priority")) : current.priority();
    LocalDate dueDate = payload.containsKey("dueDate")
        ? date(payload.get("dueDate")) : current.dueDate();
    boolean completed = payload.get("completed") instanceof Boolean value
        ? value : current.completed();
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             UPDATE todo_items
             SET title = ?, priority = ?, due_date = ?, completed = ?
             WHERE id = ? AND user_id = ?
             """)) {
      statement.setString(1, title);
      statement.setString(2, priority);
      setDate(statement, 3, dueDate);
      statement.setBoolean(4, completed);
      statement.setLong(5, id);
      statement.setLong(6, userId);
      if (statement.executeUpdate() != 1) throw new IllegalArgumentException("任务不存在");
      return find(userId, id);
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public void delete(long userId, long id) {
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM todo_items WHERE id = ? AND user_id = ?")) {
      statement.setLong(1, id);
      statement.setLong(2, userId);
      if (statement.executeUpdate() != 1) throw new IllegalArgumentException("任务不存在");
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private TodoItem find(long userId, long id) {
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT id, title, priority, due_date, completed, created_at, updated_at
             FROM todo_items WHERE id = ? AND user_id = ?
             """)) {
      statement.setLong(1, id);
      statement.setLong(2, userId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new IllegalArgumentException("任务不存在");
        return map(result);
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private static TodoItem map(ResultSet result) throws SQLException {
    Date dueDate = result.getDate("due_date");
    return new TodoItem(
        result.getLong("id"), result.getString("title"), result.getString("priority"),
        dueDate == null ? null : dueDate.toLocalDate(), result.getBoolean("completed"),
        result.getTimestamp("created_at").toLocalDateTime(),
        result.getTimestamp("updated_at").toLocalDateTime());
  }

  private static String title(Object value) {
    String title = String.valueOf(value == null ? "" : value).trim();
    if (title.isBlank()) throw new IllegalArgumentException("请输入任务内容");
    if (title.length() > 240) throw new IllegalArgumentException("任务内容不能超过 240 个字符");
    return title;
  }

  private static String priority(Object value) {
    String priority = String.valueOf(value == null ? "normal" : value).trim().toLowerCase();
    if (!List.of("low", "normal", "high").contains(priority)) {
      throw new IllegalArgumentException("优先级无效");
    }
    return priority;
  }

  private static LocalDate date(Object value) {
    String text = String.valueOf(value == null ? "" : value).trim();
    if (text.isBlank()) return null;
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException error) {
      throw new IllegalArgumentException("截止日期格式无效");
    }
  }

  private static void setDate(PreparedStatement statement, int index, LocalDate value)
      throws SQLException {
    if (value == null) statement.setNull(index, java.sql.Types.DATE);
    else statement.setDate(index, Date.valueOf(value));
  }

  private static IllegalStateException databaseError(SQLException error) {
    return new IllegalStateException("Todo 数据库操作失败：" + error.getMessage(), error);
  }

  public record TodoItem(
      long id,
      String title,
      String priority,
      LocalDate dueDate,
      boolean completed,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}
}
