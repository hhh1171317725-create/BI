package com.rockorca.bi;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
}
