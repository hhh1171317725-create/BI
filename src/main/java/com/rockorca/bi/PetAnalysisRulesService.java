package com.rockorca.bi;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 管理员维护的全站分析规则。只有持久化成功才发布新的不可变快照。 */
@Service
public class PetAnalysisRulesService {
  private static final Set<String> SCOPES = Set.of("all", "dhh", "jd", "bid");
  private static final Set<String> TYPES = Set.of("text", "condition");
  private static final Set<String> DIMENSIONS = Set.of("summary", "account", "task", "optimizer", "plan");
  private static final Set<String> OPERATORS = Set.of("gt", "gte", "lt", "lte", "eq", "ne");
  private static final Set<String> METRICS = Set.of(
      "消耗", "现金消耗", "预估佣金", "佣金", "现金利润", "预估利润", "实际利润",
      "ROI", "现金ROI", "预估ROI", "实际ROI", "注册数", "注册成本", "转化数",
      "计划累计转化数", "有效订单数", "结算数", "当前出价", "gap", "结算单价",
      "实际单价", "预估赔付", "预估eCPM");
  private static final Set<String> RULE_FIELDS = Set.of(
      "id", "title", "scope", "enabled", "type", "dimension", "content", "conditions");
  private static final Set<String> CONDITION_FIELDS = Set.of("metric", "operator", "value");
  private static final String FILE_NAME = "pet-analysis-rules.json";

  private final Path runtimeDir;
  private final ObjectMapper objectMapper;
  private Snapshot cached;
  private ResponseStatusException loadFailure;

  public PetAnalysisRulesService(RuntimeConfig config, ObjectMapper objectMapper) {
    Path configured = config.runtimeDir();
    this.runtimeDir = (configured == null ? Path.of(".runtime") : configured).toAbsolutePath().normalize();
    this.objectMapper = objectMapper;
  }

  public record Condition(String metric, String operator, double value) {}

  public record Rule(
      String id, String title, String scope, boolean enabled, String type, String dimension,
      String content, List<Condition> conditions) {
    public Rule {
      conditions = conditions == null ? null : List.copyOf(conditions);
    }
  }

  public record Snapshot(long version, Long updatedAt, List<Rule> rules) {
    public Snapshot {
      rules = List.copyOf(rules);
    }

    public List<Rule> activeFor(String scope) {
      return rules.stream().filter(rule -> rule.enabled()
          && ("all".equals(rule.scope()) || rule.scope().equals(scope))).toList();
    }
  }

  public synchronized Snapshot snapshot() {
    if (cached != null) return cached;
    if (loadFailure != null) throw loadFailure;
    try {
      Object raw = objectMapper.readValue(Files.readString(file(), StandardCharsets.UTF_8), Object.class);
      Map<?, ?> document = object(raw, "规则文件");
      fields(document, Set.of("version", "updatedAt", "rules"), "规则文件");
      long version = nonnegativeLong(document.get("version"), "规则版本");
      Object rawUpdatedAt = document.get("updatedAt");
      Long updatedAt = rawUpdatedAt == null ? null : nonnegativeLong(rawUpdatedAt, "更新时间");
      if (version > 0 && updatedAt == null) throw invalid("已保存规则缺少更新时间");
      if (version == 0 && (updatedAt != null || !(document.get("rules") instanceof List<?> list) || !list.isEmpty())) {
        throw invalid("初始规则版本格式无效");
      }
      cached = new Snapshot(version, updatedAt, validated(rulesFrom(document.get("rules"))));
    } catch (NoSuchFileException missing) {
      if (Files.notExists(file(), LinkOption.NOFOLLOW_LINKS)) {
        cached = new Snapshot(0, null, List.of());
      } else {
        loadFailure = readFailure(missing);
        throw loadFailure;
      }
    } catch (Exception error) {
      loadFailure = readFailure(error);
      throw loadFailure;
    }
    return cached;
  }

  public List<Rule> activeFor(String scope) {
    return snapshot().activeFor(scope);
  }

  public synchronized Snapshot save(long expectedVersion, List<Rule> rules) {
    if (expectedVersion < 0) throw invalid("规则版本必须是非负整数");
    List<Rule> normalized = validated(rules);
    Snapshot previous = snapshot();
    if (previous.version() != expectedVersion) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "分析规则已被其他页面修改，请重新加载后再保存");
    }
    if (previous.version() == Long.MAX_VALUE) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "分析规则版本已达到上限，无法继续保存");
    }
    Snapshot next = new Snapshot(previous.version() + 1, System.currentTimeMillis(), normalized);
    Path temporary = null;
    try {
      Files.createDirectories(runtimeDir);
      temporary = Files.createTempFile(runtimeDir, "pet-analysis-rules-", ".tmp");
      setOwnerOnlyPermissions(temporary);
      byte[] bytes = objectMapper.writeValueAsString(next).getBytes(StandardCharsets.UTF_8);
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      replace(temporary, file());
      cached = next;
      return next;
    } catch (IOException | RuntimeException error) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "保存分析规则失败，已保存的规则未改变，请稍后重试", error);
    } finally {
      if (temporary != null) {
        try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
      }
    }
  }

  /** 可替换的文件操作边界，便于验证移动失败不会发布新快照。 */
  void replace(Path temporary, Path destination) throws IOException {
    try {
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private Path file() {
    return runtimeDir.resolve(FILE_NAME);
  }

  private static ResponseStatusException readFailure(Exception error) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
        "读取分析规则失败，请修复已保存的规则文件后重启服务；原文件已保留", error);
  }

  private static void setOwnerOnlyPermissions(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // Windows 文件系统没有 POSIX 权限。
    }
  }

  static long nonnegativeLong(Object value, String label) {
    long number;
    if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
      number = ((Number) value).longValue();
    } else if (value instanceof BigInteger integer && integer.signum() >= 0 && integer.bitLength() <= 63) {
      number = integer.longValue();
    } else {
      throw invalid(label + "必须是非负整数");
    }
    if (number < 0) throw invalid(label + "必须是非负整数");
    return number;
  }

  static List<Rule> rulesFrom(Object raw) {
    if (!(raw instanceof List<?> entries)) throw invalid("规则必须是数组");
    if (entries.size() > 20) throw invalid("最多保存 20 条分析规则");
    List<Rule> rules = new ArrayList<>();
    for (Object entry : entries) {
      Map<?, ?> rule = object(entry, "规则");
      fields(rule, RULE_FIELDS, "规则");
      Object enabled = rule.get("enabled");
      if (!(enabled instanceof Boolean)) throw invalid("规则启用状态必须是布尔值");
      Object rawConditions = rule.get("conditions");
      if (!(rawConditions instanceof List<?> conditions)) throw invalid("规则条件必须是数组");
      if (conditions.size() > 5) throw invalid("每条规则最多保存 5 个条件");
      List<Condition> parsed = new ArrayList<>();
      for (Object item : conditions) {
        Map<?, ?> condition = object(item, "规则条件");
        fields(condition, CONDITION_FIELDS, "规则条件");
        Object value = condition.get("value");
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
          throw invalid("条件阈值必须是有限数字");
        }
        parsed.add(new Condition(string(condition.get("metric"), "条件指标"),
            string(condition.get("operator"), "条件运算符"), number.doubleValue()));
      }
      rules.add(new Rule(string(rule.get("id"), "规则 ID"), string(rule.get("title"), "规则标题"),
          string(rule.get("scope"), "规则范围"), (Boolean) enabled,
          string(rule.get("type"), "规则类型"), string(rule.get("dimension"), "分析维度"),
          string(rule.get("content"), "分析要求"), parsed));
    }
    return rules;
  }

  static void fields(Map<?, ?> value, Set<String> expected, String label) {
    if (!value.keySet().equals(expected)) throw invalid(label + "字段缺失或包含未知字段");
  }

  private static Map<?, ?> object(Object raw, String label) {
    if (!(raw instanceof Map<?, ?> object)) throw invalid(label + "必须是对象");
    return object;
  }

  private static String string(Object value, String label) {
    if (!(value instanceof String text)) throw invalid(label + "必须是字符串");
    return text;
  }

  private static List<Rule> validated(List<Rule> rules) {
    if (rules == null) throw invalid("规则必须是数组");
    if (rules.size() > 20) throw invalid("最多保存 20 条分析规则");
    List<Rule> normalized = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    int total = 0;
    for (Rule rule : rules) {
      if (rule == null || rule.id() == null || !rule.id().matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
        throw invalid("规则 ID 必须是有效 UUID");
      }
      String id = UUID.fromString(rule.id()).toString();
      if (!ids.add(id)) throw invalid("规则 ID 不能重复");
      String title = bounded(rule.title(), 60, "规则标题");
      String content = bounded(rule.content(), 2_000, "分析要求");
      total += content.codePointCount(0, content.length());
      if (total > 12_000) throw invalid("所有规则的分析要求合计不能超过 12000 个字符");
      if (rule.scope() == null || !SCOPES.contains(rule.scope())) throw invalid("规则范围无效");
      if (rule.type() == null || !TYPES.contains(rule.type())) throw invalid("规则类型无效");
      if (rule.dimension() == null || !DIMENSIONS.contains(rule.dimension())) throw invalid("分析维度无效");
      if (rule.conditions() == null || rule.conditions().size() > 5) throw invalid("规则条件数量无效");
      if ("text".equals(rule.type())) {
        if (!"summary".equals(rule.dimension()) || !rule.conditions().isEmpty()) {
          throw invalid("自然语言规则必须使用汇总维度且不能包含条件");
        }
      } else if (rule.conditions().isEmpty()) {
        throw invalid("条件规则至少需要一个条件");
      }
      for (Condition condition : rule.conditions()) {
        if (condition == null || condition.metric() == null || !METRICS.contains(condition.metric())
            || condition.operator() == null || !OPERATORS.contains(condition.operator()) || !Double.isFinite(condition.value())) {
          throw invalid("规则条件的指标、运算符或阈值无效");
        }
      }
      normalized.add(new Rule(id, title, rule.scope(), rule.enabled(), rule.type(), rule.dimension(), content, rule.conditions()));
    }
    return List.copyOf(normalized);
  }

  private static String bounded(String value, int max, String label) {
    if (value == null) throw invalid(label + "必须是字符串");
    int start = 0;
    int end = value.length();
    while (start < end && space(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
    while (end > start && space(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
    String normalized = value.substring(start, end);
    int length = normalized.codePointCount(0, normalized.length());
    if (length < 1 || length > max) throw invalid(label + "长度必须在 1 到 " + max + " 个字符之间");
    return normalized;
  }

  private static boolean space(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
