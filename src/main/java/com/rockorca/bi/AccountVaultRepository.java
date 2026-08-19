package com.rockorca.bi;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class AccountVaultRepository {
  public record Entry(
      long id,
      String category,
      String name,
      String accountId,
      String username,
      String secretEncrypted,
      String url,
      String materialUrl,
      String keyword,
      String channelId,
      String styleId,
      String country,
      String owner,
      String notes,
      long createdBy,
      long updatedBy,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  public record Page(List<Entry> entries, long total) {}

  public record OptionEntry(long id, String type, String value) {}

  private static final String COLUMNS = "id, category, name, account_id, username, secret_encrypted,"
      + " url, material_url, keyword_text, channel_id, style_id, country, owner_name, notes, created_by, updated_by,"
      + " created_at, updated_at";
  private final ReportRepository reports;
  private volatile boolean initialized;

  public AccountVaultRepository(ReportRepository reports) {
    this.reports = reports;
  }

  public synchronized void initialize() {
    if (initialized) return;
    try (Connection connection = reports.openConnection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS account_vault_entries (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
            category VARCHAR(40) NOT NULL DEFAULT 'ad_account',
            name VARCHAR(255) NOT NULL,
            account_id TEXT NOT NULL,
            username VARCHAR(500) NOT NULL DEFAULT '',
            secret_encrypted TEXT NOT NULL,
            url VARCHAR(2000) NOT NULL DEFAULT '',
            material_url VARCHAR(2000) NOT NULL DEFAULT '',
            keyword_text VARCHAR(1000) NOT NULL DEFAULT '',
            channel_id VARCHAR(255) NOT NULL DEFAULT '',
            style_id VARCHAR(255) NOT NULL DEFAULT '',
            country VARCHAR(255) NOT NULL DEFAULT '',
            owner_name VARCHAR(255) NOT NULL DEFAULT '',
            notes TEXT NOT NULL,
            created_by BIGINT UNSIGNED NOT NULL,
            updated_by BIGINT UNSIGNED NOT NULL,
            created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
            updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3),
            PRIMARY KEY (id),
            KEY idx_account_vault_category (category),
            KEY idx_account_vault_account_id (account_id(191)),
            KEY idx_account_vault_updated_at (updated_at)
          ) ENGINE=InnoDB COMMENT='管理员账户与链接资料库'
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS account_vault_options (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
            option_type VARCHAR(20) NOT NULL,
            option_value VARCHAR(255) NOT NULL,
            created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
            updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3),
            PRIMARY KEY (id),
            UNIQUE KEY uk_account_vault_option (option_type, option_value)
          ) ENGINE=InnoDB COMMENT='账户映射 channel 与 style ID 选项'
          """);
      migrateAccountIdColumn(connection);
      migrateMaterialUrlColumn(connection);
      statement.execute("""
          INSERT IGNORE INTO account_vault_options (option_type, option_value)
          SELECT 'channel', TRIM(channel_id)
            FROM account_vault_entries
           WHERE category = 'ad_account' AND TRIM(channel_id) <> ''
          """);
      statement.execute("""
          INSERT IGNORE INTO account_vault_options (option_type, option_value)
          SELECT 'style_id', TRIM(style_id)
            FROM account_vault_entries
           WHERE category = 'ad_account' AND TRIM(style_id) <> ''
          """);
      initialized = true;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public List<OptionEntry> listOptions() {
    initialize();
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT id, option_type, option_value FROM account_vault_options"
                 + " ORDER BY option_type, option_value")) {
      List<OptionEntry> options = new ArrayList<>();
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          options.add(new OptionEntry(
              result.getLong("id"), result.getString("option_type"),
              result.getString("option_value")));
        }
      }
      return options;
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public OptionEntry createOption(String type, String value) {
    initialize();
    try (Connection connection = reports.openConnection()) {
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT IGNORE INTO account_vault_options (option_type, option_value) VALUES (?, ?)")) {
        statement.setString(1, type);
        statement.setString(2, value);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT id, option_type, option_value FROM account_vault_options"
              + " WHERE option_type = ? AND option_value = ?")) {
        statement.setString(1, type);
        statement.setString(2, value);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next()) throw new IllegalStateException("保存选项后未能读取数据");
          return new OptionEntry(
              result.getLong("id"), result.getString("option_type"),
              result.getString("option_value"));
        }
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public void deleteOption(long id) {
    initialize();
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM account_vault_options WHERE id = ?")) {
      statement.setLong(1, id);
      if (statement.executeUpdate() != 1) throw new IllegalArgumentException("选项不存在");
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private static void migrateAccountIdColumn(Connection connection) throws SQLException {
    String dataType = "";
    try (PreparedStatement query = connection.prepareStatement("""
        SELECT DATA_TYPE
          FROM INFORMATION_SCHEMA.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'account_vault_entries'
           AND COLUMN_NAME = 'account_id'
        """)) {
      try (ResultSet result = query.executeQuery()) {
        if (result.next()) dataType = result.getString(1);
      }
    }
    if (!"text".equalsIgnoreCase(dataType)) {
      try (Statement statement = connection.createStatement()) {
        try {
          statement.execute("ALTER TABLE account_vault_entries DROP INDEX idx_account_vault_account_id");
        } catch (SQLException error) {
          if (error.getErrorCode() != 1091) throw error;
        }
        statement.execute("ALTER TABLE account_vault_entries MODIFY account_id TEXT NOT NULL");
      }
    }
    boolean hasIndex;
    try (PreparedStatement query = connection.prepareStatement("""
        SELECT 1
          FROM INFORMATION_SCHEMA.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'account_vault_entries'
           AND INDEX_NAME = 'idx_account_vault_account_id'
         LIMIT 1
        """)) {
      try (ResultSet result = query.executeQuery()) {
        hasIndex = result.next();
      }
    }
    if (!hasIndex) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE INDEX idx_account_vault_account_id"
            + " ON account_vault_entries (account_id(191))");
      }
    }
  }

  private static void migrateMaterialUrlColumn(Connection connection) throws SQLException {
    boolean exists;
    try (PreparedStatement query = connection.prepareStatement("""
        SELECT 1
          FROM INFORMATION_SCHEMA.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'account_vault_entries'
           AND COLUMN_NAME = 'material_url'
         LIMIT 1
        """)) {
      try (ResultSet result = query.executeQuery()) {
        exists = result.next();
      }
    }
    if (!exists) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("ALTER TABLE account_vault_entries"
            + " ADD COLUMN material_url VARCHAR(2000) NOT NULL DEFAULT '' AFTER url");
      }
    }
  }

  public Page list(String query, String category, int page, int pageSize) {
    initialize();
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<String> parameters = new ArrayList<>();
    if (!category.isBlank()) {
      where.append(" AND category = ?");
      parameters.add(category);
    }
    if (!query.isBlank()) {
      where.append(" AND (name LIKE ? OR account_id LIKE ? OR username LIKE ? OR url LIKE ?"
          + " OR material_url LIKE ?"
          + " OR keyword_text LIKE ? OR channel_id LIKE ? OR style_id LIKE ? OR country LIKE ?"
          + " OR owner_name LIKE ? OR notes LIKE ?)");
      String pattern = "%" + query + "%";
      for (int index = 0; index < 11; index++) parameters.add(pattern);
    }
    try (Connection connection = reports.openConnection()) {
      long total;
      try (PreparedStatement count = connection.prepareStatement(
          "SELECT COUNT(*) FROM account_vault_entries" + where)) {
        bind(count, parameters);
        try (ResultSet result = count.executeQuery()) {
          result.next();
          total = result.getLong(1);
        }
      }
      List<Entry> entries = new ArrayList<>();
      String sql = "SELECT " + COLUMNS + " FROM account_vault_entries" + where
          + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        int next = bind(statement, parameters);
        statement.setInt(next++, pageSize);
        statement.setInt(next, (page - 1) * pageSize);
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) entries.add(map(result));
        }
      }
      return new Page(entries, total);
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public Entry find(long id) {
    initialize();
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT " + COLUMNS + " FROM account_vault_entries WHERE id = ?")) {
      statement.setLong(1, id);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new IllegalArgumentException("账户资料不存在");
        return map(result);
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public Entry create(Entry entry) {
    initialize();
    String sql = """
        INSERT INTO account_vault_entries
          (category, name, account_id, username, secret_encrypted, url, material_url, keyword_text,
           channel_id, style_id, country, owner_name, notes, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      bindEntry(statement, entry);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        if (!keys.next()) throw new IllegalStateException("新增资料后未返回 ID");
        return find(keys.getLong(1));
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public void createAll(List<Entry> entries) {
    initialize();
    if (entries.isEmpty()) return;
    String sql = """
        INSERT INTO account_vault_entries
          (category, name, account_id, username, secret_encrypted, url, material_url, keyword_text,
           channel_id, style_id, country, owner_name, notes, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (Connection connection = reports.openConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (Entry entry : entries) {
          bindEntry(statement, entry);
          statement.addBatch();
        }
        statement.executeBatch();
        connection.commit();
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public Entry update(long id, Entry entry) {
    initialize();
    String sql = """
        UPDATE account_vault_entries
           SET category = ?, name = ?, account_id = ?, username = ?, secret_encrypted = ?,
               url = ?, material_url = ?, keyword_text = ?, channel_id = ?, style_id = ?, country = ?,
               owner_name = ?, notes = ?, updated_by = ?
         WHERE id = ?
        """;
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, entry.category());
      statement.setString(2, entry.name());
      statement.setString(3, entry.accountId());
      statement.setString(4, entry.username());
      statement.setString(5, entry.secretEncrypted());
      statement.setString(6, entry.url());
      statement.setString(7, entry.materialUrl());
      statement.setString(8, entry.keyword());
      statement.setString(9, entry.channelId());
      statement.setString(10, entry.styleId());
      statement.setString(11, entry.country());
      statement.setString(12, entry.owner());
      statement.setString(13, entry.notes());
      statement.setLong(14, entry.updatedBy());
      statement.setLong(15, id);
      if (statement.executeUpdate() != 1) throw new IllegalArgumentException("账户资料不存在");
      return find(id);
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  public void delete(long id) {
    initialize();
    try (Connection connection = reports.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM account_vault_entries WHERE id = ?")) {
      statement.setLong(1, id);
      if (statement.executeUpdate() != 1) throw new IllegalArgumentException("账户资料不存在");
    } catch (SQLException error) {
      throw databaseError(error);
    }
  }

  private static int bind(PreparedStatement statement, List<String> values) throws SQLException {
    int index = 1;
    for (String value : values) statement.setString(index++, value);
    return index;
  }

  private static void bindEntry(PreparedStatement statement, Entry entry) throws SQLException {
    statement.setString(1, entry.category());
    statement.setString(2, entry.name());
    statement.setString(3, entry.accountId());
    statement.setString(4, entry.username());
    statement.setString(5, entry.secretEncrypted());
    statement.setString(6, entry.url());
    statement.setString(7, entry.materialUrl());
    statement.setString(8, entry.keyword());
    statement.setString(9, entry.channelId());
    statement.setString(10, entry.styleId());
    statement.setString(11, entry.country());
    statement.setString(12, entry.owner());
    statement.setString(13, entry.notes());
    statement.setLong(14, entry.createdBy());
    statement.setLong(15, entry.updatedBy());
  }

  private static Entry map(ResultSet result) throws SQLException {
    return new Entry(
        result.getLong("id"), result.getString("category"), result.getString("name"),
        result.getString("account_id"), result.getString("username"),
        result.getString("secret_encrypted"), result.getString("url"),
        result.getString("material_url"),
        result.getString("keyword_text"), result.getString("channel_id"),
        result.getString("style_id"), result.getString("country"), result.getString("owner_name"),
        result.getString("notes"), result.getLong("created_by"), result.getLong("updated_by"),
        result.getTimestamp("created_at").toLocalDateTime(),
        result.getTimestamp("updated_at").toLocalDateTime());
  }

  private static IllegalStateException databaseError(SQLException error) {
    return new IllegalStateException("账户资料数据库操作失败：" + error.getMessage(), error);
  }
}
