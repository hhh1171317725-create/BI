package com.rockorca.bi;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
  public record UserAccount(
      long id,
      String username,
      String passwordHash,
      String role,
      boolean active,
      int sessionVersion,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime lastLoginAt) {
    public boolean admin() {
      return "admin".equals(role);
    }
  }

  private static final String USER_COLUMNS =
      "id, username, password_hash, role, active, session_version,"
          + " created_at, updated_at, last_login_at";
  private final ReportRepository reports;
  private volatile boolean initialized;

  public UserRepository(ReportRepository reports) {
    this.reports = reports;
  }

  public synchronized void initialize(String bootstrapUsername, String bootstrapPasswordHash) {
    if (initialized) return;
    try (Connection connection = reports.openConnection();
         Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS report_users (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
            username VARCHAR(64) NOT NULL,
            password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'user',
            active TINYINT(1) NOT NULL DEFAULT 1,
            session_version INT UNSIGNED NOT NULL DEFAULT 1,
            created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
            updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3),
            last_login_at DATETIME(3) NULL,
            PRIMARY KEY (id),
            UNIQUE KEY uk_report_users_username (username),
            KEY idx_report_users_role_active (role, active)
          ) ENGINE=InnoDB COMMENT='报表系统登录用户'
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS report_user_visibility (
            user_id BIGINT UNSIGNED NOT NULL,
            dhh_visible TINYINT(1) NOT NULL DEFAULT 1,
            jd_visible TINYINT(1) NOT NULL DEFAULT 1,
            jd_low_activity_visible TINYINT(1) NOT NULL DEFAULT 1,
            adpflux_visible TINYINT(1) NOT NULL DEFAULT 1,
            updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3),
            PRIMARY KEY (user_id)
          ) ENGINE=InnoDB COMMENT='用户日报板块可见权限'
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS report_user_tool_visibility (
            user_id BIGINT UNSIGNED NOT NULL,
            tool_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
            visible TINYINT(1) NOT NULL DEFAULT 1,
            updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3),
            PRIMARY KEY (user_id, tool_key)
          ) ENGINE=InnoDB COMMENT='用户工具中心可见权限'
          """);
      boolean empty;
      try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM report_users")) {
        result.next();
        empty = result.getLong(1) == 0;
      }
      if (empty) {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO report_users (username, password_hash, role, active)
            VALUES (?, ?, 'admin', 1)
            """)) {
          insert.setString(1, bootstrapUsername);
          insert.setString(2, bootstrapPasswordHash);
          insert.executeUpdate();
        }
      }
      initialized = true;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public Optional<UserAccount> findByUsername(String username) {
    return findOne("SELECT " + USER_COLUMNS + " FROM report_users WHERE username = ?", username);
  }

  public Optional<UserAccount> findById(long id) {
    return findOne("SELECT " + USER_COLUMNS + " FROM report_users WHERE id = ?", id);
  }

  public List<UserAccount> list() {
    List<UserAccount> users = new ArrayList<>();
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + USER_COLUMNS + " FROM report_users ORDER BY id ASC");
         ResultSet result = statement.executeQuery()) {
      while (result.next()) users.add(mapUser(result));
      return users;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public UserAccount create(String username, String passwordHash, String role) {
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             INSERT INTO report_users (username, password_hash, role, active)
             VALUES (?, ?, ?, 1)
             """, Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, username);
      statement.setString(2, passwordHash);
      statement.setString(3, role);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        if (!keys.next()) throw new IllegalStateException("创建用户后未返回用户 ID");
        return findById(keys.getLong(1)).orElseThrow();
      }
    } catch (SQLException error) {
      if ("23000".equals(error.getSQLState())) {
        throw new IllegalArgumentException("用户名已存在");
      }
      throw databaseError(error);
    }
  }

  public UserAccount updatePassword(long id, String passwordHash) {
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             UPDATE report_users
             SET password_hash = ?, session_version = session_version + 1
             WHERE id = ?
             """)) {
      statement.setString(1, passwordHash);
      statement.setLong(2, id);
      if (statement.executeUpdate() != 1) throw new IllegalArgumentException("用户不存在");
      return findById(id).orElseThrow();
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public UserAccount setActive(long id, boolean active) {
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             UPDATE report_users
             SET active = ?, session_version = session_version + 1
             WHERE id = ?
             """)) {
      statement.setBoolean(1, active);
      statement.setLong(2, id);
      if (statement.executeUpdate() != 1) throw new IllegalArgumentException("用户不存在");
      return findById(id).orElseThrow();
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public Map<String, Boolean> reportVisibility(long userId) {
    Map<String, Boolean> visibility = defaultReportVisibility();
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT dhh_visible, jd_visible, jd_low_activity_visible, adpflux_visible
             FROM report_user_visibility
             WHERE user_id = ?
             """)) {
      statement.setLong(1, userId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) return visibility;
        visibility.put("dhh", result.getBoolean("dhh_visible"));
        visibility.put("jd", result.getBoolean("jd_visible"));
        visibility.put("jdLowActivity", result.getBoolean("jd_low_activity_visible"));
        visibility.put("adpflux", result.getBoolean("adpflux_visible"));
        return visibility;
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public Map<String, Boolean> saveReportVisibility(
      long userId, boolean dhh, boolean jd, boolean jdLowActivity, boolean adpflux) {
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             INSERT INTO report_user_visibility
               (user_id, dhh_visible, jd_visible, jd_low_activity_visible, adpflux_visible)
             VALUES (?, ?, ?, ?, ?)
             ON DUPLICATE KEY UPDATE
               dhh_visible = VALUES(dhh_visible),
               jd_visible = VALUES(jd_visible),
               jd_low_activity_visible = VALUES(jd_low_activity_visible),
               adpflux_visible = VALUES(adpflux_visible)
             """)) {
      statement.setLong(1, userId);
      statement.setBoolean(2, dhh);
      statement.setBoolean(3, jd);
      statement.setBoolean(4, jdLowActivity);
      statement.setBoolean(5, adpflux);
      statement.executeUpdate();
      return reportVisibility(userId);
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public Map<String, Boolean> toolVisibility(long userId) {
    Map<String, Boolean> visibility = defaultToolVisibility();
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT tool_key, visible
             FROM report_user_tool_visibility
             WHERE user_id = ?
             """)) {
      statement.setLong(1, userId);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          String key = result.getString("tool_key");
          if (visibility.containsKey(key)) visibility.put(key, result.getBoolean("visible"));
        }
        return visibility;
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public Map<String, Boolean> saveToolVisibility(
      long userId, Map<String, Boolean> visibility) {
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             INSERT INTO report_user_tool_visibility (user_id, tool_key, visible)
             VALUES (?, ?, ?)
             ON DUPLICATE KEY UPDATE visible = VALUES(visible)
             """)) {
      for (Map.Entry<String, Boolean> entry : defaultToolVisibility().entrySet()) {
        statement.setLong(1, userId);
        statement.setString(2, entry.getKey());
        statement.setBoolean(3, !Boolean.FALSE.equals(visibility.get(entry.getKey())));
        statement.addBatch();
      }
      statement.executeBatch();
      return toolVisibility(userId);
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public void markLogin(long id) {
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "UPDATE report_users SET last_login_at = CURRENT_TIMESTAMP(3) WHERE id = ?")) {
      statement.setLong(1, id);
      statement.executeUpdate();
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private Optional<UserAccount> findOne(String sql, Object value) {
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      if (value instanceof Number number) {
        statement.setLong(1, number.longValue());
      } else {
        statement.setString(1, String.valueOf(value));
      }
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(mapUser(result)) : Optional.empty();
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private static UserAccount mapUser(ResultSet result) throws SQLException {
    return new UserAccount(
        result.getLong("id"),
        result.getString("username"),
        result.getString("password_hash"),
        result.getString("role"),
        result.getBoolean("active"),
        result.getInt("session_version"),
        result.getTimestamp("created_at").toLocalDateTime(),
        result.getTimestamp("updated_at").toLocalDateTime(),
        result.getTimestamp("last_login_at") == null
            ? null
            : result.getTimestamp("last_login_at").toLocalDateTime());
  }

  private static IllegalStateException databaseError(SQLException error) {
    return new IllegalStateException("用户数据库操作失败：" + error.getMessage(), error);
  }

  private static Map<String, Boolean> defaultReportVisibility() {
    Map<String, Boolean> visibility = new LinkedHashMap<>();
    visibility.put("dhh", true);
    visibility.put("jd", true);
    visibility.put("jdLowActivity", true);
    visibility.put("adpflux", true);
    return visibility;
  }

  private static Map<String, Boolean> defaultToolVisibility() {
    Map<String, Boolean> visibility = new LinkedHashMap<>();
    visibility.put("todo", true);
    visibility.put("terminal", false);
    visibility.put("accountVault", true);
    visibility.put("adpfluxHelper", false);
    visibility.put("mailDingtalk", false);
    visibility.put("chat", true);
    visibility.put("deeplink", true);
    visibility.put("deeplinkAccount", true);
    visibility.put("jdImages", true);
    visibility.put("bidMonitor", true);
    return visibility;
  }
}
