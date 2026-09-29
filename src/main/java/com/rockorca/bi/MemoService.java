package com.rockorca.bi;

import java.sql.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Personal notes. Every query is scoped to the authenticated owner. */
@Service
public class MemoService {
  private final ReportRepository reports;
  private volatile boolean initialized;

  public MemoService(ReportRepository reports) { this.reports = reports; }

  private synchronized void initialize() throws SQLException {
    if (initialized) return;
    try (Connection connection = reports.openConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS personal_memos (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            user_id BIGINT UNSIGNED NOT NULL,
            title VARCHAR(160) NOT NULL,
            content MEDIUMTEXT NOT NULL,
            tags VARCHAR(256) NOT NULL DEFAULT '',
            pinned BOOLEAN NOT NULL DEFAULT FALSE,
            deleted BOOLEAN NOT NULL DEFAULT FALSE,
            version BIGINT NOT NULL DEFAULT 1,
            created_at BIGINT NOT NULL,
            updated_at BIGINT NOT NULL,
            KEY idx_memo_owner (user_id, deleted, pinned, updated_at, id)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
          """);
      initialized = true;
    }
  }

  static String field(Map<String, Object> payload, String key, int limit) {
    Object raw = payload.getOrDefault(key, "");
    if (!(raw instanceof String value) || value.length() > limit)
      throw new IllegalArgumentException(key + " 格式错误或超过长度限制（" + limit + "）");
    return value;
  }

  static String tags(String value) {
    LinkedHashSet<String> tags = new LinkedHashSet<>();
    for (String part : value.split("[,，\\n]")) {
      String tag = part.strip();
      if (tag.isEmpty()) continue;
      if (tag.length() > 24) throw new IllegalArgumentException("每个标签最多 24 个字符");
      tags.add(tag);
    }
    if (tags.size() > 10) throw new IllegalArgumentException("最多添加 10 个标签");
    return String.join(",", tags);
  }

  static String literalLike(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  static long version(Object value) {
    try {
      long number = Long.parseLong(String.valueOf(value));
      if (number > 0) return number;
    } catch (NumberFormatException ignored) { }
    throw new IllegalArgumentException("缺少有效版本，请重新打开备忘录");
  }

  private static Map<String, Object> row(ResultSet result, boolean detail) throws SQLException {
    Map<String, Object> item = new LinkedHashMap<>();
    for (String key : List.of("id", "version")) item.put(key, result.getLong(key));
    for (String key : List.of("title", "tags")) item.put(key, result.getString(key));
    item.put(detail ? "content" : "preview", result.getString(detail ? "content" : "preview"));
    item.put("pinned", result.getBoolean("pinned"));
    item.put("deleted", result.getBoolean("deleted"));
    item.put("createdAt", result.getLong("created_at"));
    item.put("updatedAt", result.getLong("updated_at"));
    return item;
  }

  public Map<String, Object> list(long owner, String query, String tag, String view, int offset) throws SQLException {
    if (query.length() > 100 || tag.length() > 24 || offset < 0 || offset > 100000)
      throw new IllegalArgumentException("查询条件超出范围");
    if (!Set.of("all", "pinned", "trash").contains(view)) throw new IllegalArgumentException("无效的列表类型");
    initialize();
    StringBuilder sql = new StringBuilder("""
        SELECT id,title,LEFT(content,180) AS preview,tags,pinned,deleted,version,created_at,updated_at
        FROM personal_memos WHERE user_id=? AND deleted=?
        """);
    List<Object> args = new ArrayList<>(List.of(owner, view.equals("trash")));
    if (view.equals("pinned")) sql.append(" AND pinned=TRUE");
    if (!tag.isBlank()) { sql.append(" AND FIND_IN_SET(?,tags)>0"); args.add(tag); }
    // Bounded literal keyword search supports Chinese and never treats user input as SQL wildcards.
    String[] terms = query.strip().isEmpty() ? new String[0] : query.strip().split("\\s+");
    if (terms.length > 8) throw new IllegalArgumentException("最多输入 8 个搜索关键词");
    for (String term : terms) {
      sql.append(" AND (title LIKE ? ESCAPE '!' OR content LIKE ? ESCAPE '!' OR tags LIKE ? ESCAPE '!')");
      for (int i = 0; i < 3; i++) args.add(literalLike(term));
    }
    sql.append(" ORDER BY pinned DESC,updated_at DESC,id DESC LIMIT 31 OFFSET ?"); args.add(offset);
    List<Map<String, Object>> items = new ArrayList<>();
    try (Connection connection = reports.openConnection(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      for (int i = 0; i < args.size(); i++) statement.setObject(i + 1, args.get(i));
      try (ResultSet result = statement.executeQuery()) { while (result.next()) items.add(row(result, false)); }
    }
    boolean more = items.size() > 30;
    if (more) items.removeLast();
    return Map.of("items", items, "hasMore", more, "nextOffset", offset + items.size());
  }

  public Map<String, Object> meta(long owner) throws SQLException {
    initialize();
    Map<String, Integer> labels = new TreeMap<>();
    int total = 0, pinned = 0, trash = 0;
    try (Connection connection = reports.openConnection(); PreparedStatement statement = connection.prepareStatement(
        "SELECT tags,pinned,deleted,COUNT(*) AS amount FROM personal_memos WHERE user_id=? GROUP BY tags,pinned,deleted")) {
      statement.setLong(1, owner);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          int count = result.getInt("amount");
          if (result.getBoolean("deleted")) { trash += count; continue; }
          total += count;
          if (result.getBoolean("pinned")) pinned += count;
          for (String label : result.getString("tags").split(","))
            if (!label.isBlank()) labels.merge(label, count, Integer::sum);
        }
      }
    }
    return Map.of("total", total, "pinned", pinned, "trash", trash, "tags", labels);
  }

  public Map<String, Object> get(long owner, long id) throws SQLException {
    initialize();
    try (Connection connection = reports.openConnection()) { return find(connection, owner, id); }
  }

  private Map<String, Object> find(Connection connection, long owner, long id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM personal_memos WHERE id=? AND user_id=?")) {
      statement.setLong(1, id); statement.setLong(2, owner);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "备忘录不存在");
        return row(result, true);
      }
    }
  }

  public Map<String, Object> save(long owner, Long id, Map<String, Object> payload) throws SQLException {
    String title = field(payload, "title", 160).strip();
    if (title.isBlank()) throw new IllegalArgumentException("请填写标题");
    String content = field(payload, "content", 50000);
    String labels = tags(field(payload, "tags", 256));
    if (!(payload.get("pinned") instanceof Boolean)) throw new IllegalArgumentException("置顶参数无效");
    long revision = id == null ? 1 : version(payload.get("version"));
    initialize();
    long now = System.currentTimeMillis();
    try (Connection connection = reports.openConnection()) {
      connection.setAutoCommit(false);
      try {
        String sql = id == null
            ? "INSERT INTO personal_memos(title,content,tags,pinned,updated_at,user_id,created_at) VALUES (?,?,?,?,?,?,?)"
            : "UPDATE personal_memos SET title=?,content=?,tags=?,pinned=?,updated_at=?,version=version+1 WHERE user_id=? AND id=? AND version=? AND deleted=FALSE";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
          statement.setString(1, title); statement.setString(2, content); statement.setString(3, labels);
          statement.setBoolean(4, (Boolean) payload.get("pinned")); statement.setLong(5, now); statement.setLong(6, owner);
          statement.setLong(7, id == null ? now : id);
          if (id != null) statement.setLong(8, revision);
          if (statement.executeUpdate() != 1) throw conflict();
          if (id == null) try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("创建备忘录失败");
            id = keys.getLong(1);
          }
        }
        Map<String, Object> item = find(connection, owner, id);
        connection.commit();
        return item;
      } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
    }
  }

  public void trash(long owner, long id, long revision, boolean deleted) throws SQLException {
    initialize();
    try (Connection connection = reports.openConnection(); PreparedStatement statement = connection.prepareStatement(
        "UPDATE personal_memos SET deleted=?,updated_at=?,version=version+1 WHERE id=? AND user_id=? AND version=? AND deleted=?")) {
      statement.setBoolean(1, deleted); statement.setLong(2, System.currentTimeMillis());
      statement.setLong(3, id); statement.setLong(4, owner); statement.setLong(5, version(revision));
      statement.setBoolean(6, !deleted);
      if (statement.executeUpdate() != 1) throw conflict();
    }
  }

  private static ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "备忘录已变更或不存在，请先复制未保存内容，再重新打开核对");
  }
}
