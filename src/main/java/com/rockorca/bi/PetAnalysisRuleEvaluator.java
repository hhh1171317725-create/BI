package com.rockorca.bi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.rockorca.bi.PetAnalysisRulesService.Condition;
import com.rockorca.bi.PetAnalysisRulesService.Rule;

/** Deterministic, read-only comparisons against the supplied dimension rows. */
public final class PetAnalysisRuleEvaluator {
  private static final int MAX_RULES = 20;
  private static final int MAX_CONDITIONS = 5;
  private static final int MAX_HITS = 10;
  private static final int MAX_UNKNOWNS = 3;
  private static final List<String> DIMENSIONS = List.of("summary", "account", "task", "optimizer", "plan");
  private static final List<String> NAME_FIELDS = List.of(
      "优化师", "任务名", "任务", "账户名称", "媒体账户名称", "账户", "账户ID", "媒体账户ID", "计划", "计划ID", "项目");
  private static final Set<String> METRICS = Set.of(
      "消耗", "现金消耗", "预估佣金", "佣金", "现金利润", "预估利润", "实际利润",
      "ROI", "现金ROI", "预估ROI", "实际ROI", "注册数", "注册成本", "转化数",
      "计划累计转化数", "有效订单数", "结算数", "当前出价", "gap", "结算单价",
      "实际单价", "预估赔付", "预估eCPM");

  private PetAnalysisRuleEvaluator() {}

  /** Scope selection belongs to the caller; this method never reads another report or executes text. */
  public static Map<String, Object> evaluate(
      List<Rule> rules, Map<String, List<Map<String, Object>>> dimensions,
      Map<String, String> coverageNotes) {
    List<Map<String, Object>> results = new ArrayList<>();
    Map<String, String> notes = new LinkedHashMap<>();
    if (coverageNotes != null) {
      for (String dimension : DIMENSIONS) {
        String note = bounded(coverageNotes.get(dimension), 600);
        if (!note.isBlank()) notes.put(dimension, note);
      }
    }
    int conditionCount = 0;
    int textCount = 0;
    int activeCount = 0;
    if (rules != null) {
      for (Rule rule : rules) {
        if (rule == null || !rule.enabled()) continue;
        activeCount++;
        if (results.size() >= MAX_RULES) continue;
        Map<String, Object> result = metadata(rule);
        String note = notes.getOrDefault(rule.dimension(), "");
        if (!note.isBlank()) result.put("coverageNote", note);
        if (!"condition".equals(rule.type())) {
          textCount++;
          result.put("status", "requires_model");
          result.put("reason", "自然语言规则需要已配置的大模型解读，本地未执行。");
        } else {
          conditionCount++;
          List<Map<String, Object>> rows = dimensions == null ? null : dimensions.get(rule.dimension());
          evaluateRows(rule, rows == null ? List.of() : rows, result);
        }
        results.add(result);
      }
    }
    Map<String, Object> evaluation = new LinkedHashMap<>();
    evaluation.put("rules", results);
    evaluation.put("ruleCount", results.size());
    evaluation.put("conditionRuleCount", conditionCount);
    evaluation.put("textRuleCount", textCount);
    evaluation.put("evaluatedConditionRuleCount", conditionCount);
    evaluation.put("coverageNotes", notes);
    evaluation.put("truncatedRules", activeCount > MAX_RULES);
    return evaluation;
  }

  private static Map<String, Object> metadata(Rule rule) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", bounded(rule.id(), 36));
    result.put("title", bounded(rule.title(), 60));
    result.put("scope", bounded(rule.scope(), 16));
    result.put("type", bounded(rule.type(), 16));
    result.put("dimension", bounded(rule.dimension(), 24));
    result.put("content", bounded(rule.content(), 2_000));
    return result;
  }

  private static void evaluateRows(Rule rule, List<Map<String, Object>> rows, Map<String, Object> result) {
    int hits = 0;
    int unknowns = 0;
    int shownHits = 0;
    int shownUnknowns = 0;
    List<Map<String, Object>> evidence = new ArrayList<>();
    List<Condition> conditions = rule.conditions();
    boolean invalid = conditions == null || conditions.isEmpty() || conditions.size() > MAX_CONDITIONS;
    for (int index = 0; index < rows.size(); index++) {
      Map<String, Object> row = rows.get(index);
      boolean anyFalse = false;
      boolean anyUnknown = invalid;
      if (!invalid) {
        for (Condition condition : conditions) {
          Boolean matches = matches(condition, conditionValue(row, condition));
          anyFalse |= Boolean.FALSE.equals(matches);
          anyUnknown |= matches == null;
        }
      }
      // AND in three-valued logic: false wins over unknown.
      String status = anyFalse ? "clear" : anyUnknown ? "unknown" : "hit";
      if ("hit".equals(status)) hits++;
      if ("unknown".equals(status)) unknowns++;
      if ("hit".equals(status) && shownHits < MAX_HITS
          || "unknown".equals(status) && shownUnknowns < MAX_UNKNOWNS) {
        Map<String, Object> item = new LinkedHashMap<>();
        Map<String, String> identifiers = identifiers(row);
        item.put("name", objectName(rule.dimension(), identifiers, index));
        item.put("identifiers", identifiers);
        item.put("rowIndex", index + 1);
        item.put("status", status);
        List<Map<String, Object>> comparisons = new ArrayList<>();
        if (!invalid) for (Condition condition : conditions) comparisons.add(compare(row, condition));
        item.put("conditions", comparisons);
        if (invalid) item.put("reason", "规则条件无效，未比较。");
        evidence.add(item);
        if ("hit".equals(status)) shownHits++;
        else shownUnknowns++;
      }
    }
    result.put("status", rows.isEmpty() ? "no_data" : hits > 0 ? "hit" : unknowns > 0 ? "unknown" : "clear");
    result.put("hitCount", hits);
    result.put("unknownCount", unknowns);
    result.put("checkedCount", rows.size() - unknowns);
    result.put("totalCount", rows.size());
    result.put("nonHitCount", rows.size() - hits - unknowns);
    result.put("evidence", evidence);
    result.put("evidenceTruncated", hits > shownHits || unknowns > shownUnknowns);
  }

  private static Map<String, Object> compare(Map<String, Object> row, Condition condition) {
    Map<String, Object> comparison = new LinkedHashMap<>();
    String metric = condition == null ? "" : condition.metric();
    String operator = condition == null ? "" : condition.operator();
    double threshold = condition == null ? Double.NaN : condition.value();
    MetricValue actual = conditionValue(row, condition);
    comparison.put("metric", bounded(metric, 40));
    comparison.put("operator", bounded(operator, 10));
    comparison.put("threshold", Double.isFinite(threshold) ? threshold : null);
    comparison.put("actual", actual.value());
    if (!actual.source().isBlank()) comparison.put("source", actual.source());
    Boolean matches = matches(condition, actual);
    comparison.put("status", matches == null ? "unknown" : matches ? "true" : "false");
    if (matches == null) comparison.put("reason", !actual.reason().isBlank() ? actual.reason() : "运算符或阈值无效");
    return comparison;
  }

  private static MetricValue conditionValue(Map<String, Object> row, Condition condition) {
    String metric = condition == null || condition.metric() == null ? "" : condition.metric();
    return METRICS.contains(metric) ? metric(row, metric) : new MetricValue(null, "", "不支持的指标");
  }

  private static Boolean matches(Condition condition, MetricValue actual) {
    return condition == null || actual.value() == null || !Double.isFinite(condition.value())
        ? null : comparison(actual.value(), condition.operator(), condition.value());
  }

  private static Boolean comparison(double actual, String operator, double threshold) {
    if (operator == null) return null;
    return switch (operator) {
      case "gt" -> actual > threshold;
      case "gte" -> actual >= threshold;
      case "lt" -> actual < threshold;
      case "lte" -> actual <= threshold;
      case "eq" -> actual == threshold;
      case "ne" -> actual != threshold;
      default -> null;
    };
  }

  private record MetricValue(Double value, String source, String reason) {}

  private static MetricValue metric(Map<String, Object> row, String metric) {
    if (row == null) return unavailable(metric);
    if ("注册成本".equals(metric)) {
      if (!positive(row.get("注册数"))) return denominator("注册数");
      Double supplied = finite(row.get(metric));
      if (supplied != null) return available(supplied, metric);
      Double spend = finite(row.get("消耗"));
      if (spend == null) return unavailable("消耗");
      double derived = spend / finite(row.get("注册数"));
      return Double.isFinite(derived) ? available(derived, "消耗/注册数") : unavailable(metric);
    }
    if (Set.of("ROI", "现金ROI", "预估ROI", "实际ROI").contains(metric)) {
      // Cash ROI uses a different denominator and cannot stand in for ordinary ROI.
      List<String> sources = "ROI".equals(metric)
          ? List.of(row.containsKey("ROI") ? "ROI" : "预估ROI") : List.of(metric);
      MetricValue invalidDenominator = null;
      for (String source : sources) {
        Double value = finite(row.get(source));
        if (value == null) continue;
        String denominator = "现金ROI".equals(source) ? "现金消耗" : "消耗";
        if (positive(row.get(denominator))) return available(value, source);
        if (invalidDenominator == null) invalidDenominator = denominator(denominator);
      }
      return invalidDenominator == null ? unavailable(metric) : invalidDenominator;
    }
    // Daily settlement unit prices are ratios; bid prices may be supplied reference values.
    if ("结算单价".equals(metric) && row.containsKey("结算数") && !positive(row.get("结算数"))) {
      return denominator("结算数");
    }
    List<String> sources = switch (metric) {
      case "佣金" -> List.of("佣金", "预估佣金", "预估佣金合计");
      case "预估佣金" -> List.of("预估佣金", "佣金", "预估佣金合计");
      case "转化数" -> List.of("转化数", "计费转化数");
      case "预估赔付" -> List.of("预估赔付", "条件内预估赔付金额");
      default -> List.of(metric);
    };
    for (String source : sources) {
      Double value = finite(row.get(source));
      if (value != null) return available(value, source);
    }
    return unavailable(metric);
  }

  private static MetricValue available(double value, String source) {
    return new MetricValue(value, source, "");
  }

  private static MetricValue unavailable(String metric) {
    return new MetricValue(null, "", bounded(metric, 40) + "缺失或不是有限数字");
  }

  private static MetricValue denominator(String metric) {
    return new MetricValue(null, "", metric + "分母缺失、无效或不大于零");
  }

  private static boolean positive(Object value) {
    Double number = finite(value);
    return number != null && number > 0;
  }

  private static Double finite(Object value) {
    try {
      double number;
      if (value instanceof Number numeric) number = numeric.doubleValue();
      else if (value instanceof String text && text.length() <= 80 && !text.isBlank()) number = Double.parseDouble(text.trim());
      else return null;
      return Double.isFinite(number) ? number : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static Map<String, String> identifiers(Map<String, Object> row) {
    Map<String, String> identifiers = new LinkedHashMap<>();
    if (row == null) return identifiers;
    for (String field : NAME_FIELDS) {
      Object value = row.get(field);
      String text = value instanceof String string ? bounded(string, 120)
          : value instanceof Number && finite(value) != null ? bounded(value.toString(), 120) : "";
      if (!text.isBlank()) identifiers.put(field, text);
    }
    return identifiers;
  }

  private static String objectName(String dimension, Map<String, String> identifiers, int index) {
    List<String> preferred = switch (dimension == null ? "" : dimension) {
      case "account" -> List.of("账户名称", "媒体账户名称", "账户", "账户ID", "媒体账户ID");
      case "task" -> List.of("任务名", "任务");
      case "optimizer" -> List.of("优化师");
      case "plan" -> List.of("计划", "计划ID");
      default -> List.of();
    };
    for (String field : preferred) {
      if (identifiers.containsKey(field)) return identifiers.get(field);
    }
    if ("summary".equals(dimension)) return "当前范围汇总";
    return identifiers.isEmpty() ? "对象 " + (index + 1) : identifiers.values().iterator().next();
  }

  /** Plain text for local fallback; free-form rule text remains an analysis request only. */
  public static String localReply(Map<String, Object> evaluation) {
    if (evaluation == null || !(evaluation.get("rules") instanceof List<?> rules) || rules.isEmpty()) return "";
    StringBuilder reply = new StringBuilder("自定义分析规则：\n");
    boolean textRules = false;
    for (Object entry : rules) {
      if (!(entry instanceof Map<?, ?> rule)) continue;
      if ("requires_model".equals(rule.get("status"))) {
        textRules = true;
        continue;
      }
      reply.append("- ").append(bounded(rule.get("title") instanceof String title ? title : "条件规则", 60))
          .append("：已判定 ").append(count(rule.get("checkedCount"))).append("/").append(count(rule.get("totalCount")))
          .append(" 个对象，命中 ").append(count(rule.get("hitCount"))).append(" 个，无法判定 ")
          .append(count(rule.get("unknownCount"))).append(" 个。");
      if ("no_data".equals(rule.get("status"))) reply.append("当前范围未提供该维度数据。");
      if (rule.get("coverageNote") instanceof String note && !note.isBlank()) reply.append(" 范围说明：").append(bounded(note, 600));
      reply.append('\n');
      if (rule.get("evidence") instanceof List<?> evidence) {
        for (Map<?, ?> row : replyEvidence(evidence)) {
          reply.append("  ").append(bounded(row.get("name") instanceof String name ? name : "对象", 120))
              .append("（").append("hit".equals(row.get("status")) ? "命中" : "无法判定").append("）：");
          if (row.get("conditions") instanceof List<?> comparisons) {
            int at = 0;
            for (Object comparison : comparisons) {
              if (!(comparison instanceof Map<?, ?> condition)) continue;
              if (at++ > 0) reply.append("；");
              reply.append(bounded(condition.get("metric") instanceof String metric ? metric : "指标", 40));
              if ("unknown".equals(condition.get("status"))) {
                reply.append("未知（").append(bounded(condition.get("reason") instanceof String reason ? reason : "数据不足", 100)).append("）");
              } else {
                reply.append(' ').append(number(condition.get("actual"))).append(' ')
                    .append(symbol(condition.get("operator"))).append(' ').append(number(condition.get("threshold")));
                if (condition.get("source") instanceof String source && !source.equals(condition.get("metric"))) {
                  reply.append("（来源：").append(bounded(source, 40)).append("）");
                }
              }
            }
          }
          reply.append('\n');
        }
      }
      if (count(rule.get("hitCount")) > 0 && rule.get("content") instanceof String content && !content.isBlank()) {
        reply.append("  分析要求：").append(bounded(content, 240)).append('\n');
      }
      if (Boolean.TRUE.equals(rule.get("evidenceTruncated"))) reply.append("  以上仅展示部分证据，统计覆盖本次提供的全部对象。\n");
      if (reply.length() >= 10_000) break;
    }
    if (textRules || count(evaluation.get("textRuleCount")) > 0) {
      reply.append("自然语言规则需要已配置的大模型解读，本地未执行。\n");
    }
    if (Boolean.TRUE.equals(evaluation.get("truncatedRules"))) reply.append("本次最多评估 20 条启用规则。\n");
    return bounded(reply.toString().stripTrailing(), 12_000);
  }

  private static List<Map<?, ?>> replyEvidence(List<?> evidence) {
    List<Map<?, ?>> examples = new ArrayList<>();
    for (String status : List.of("hit", "unknown", "hit")) {
      int limit = "hit".equals(status) && examples.isEmpty() ? 2 : 3;
      for (Object item : evidence) {
        if (examples.size() >= limit) break;
        if (item instanceof Map<?, ?> row && status.equals(row.get("status")) && !examples.contains(row)) examples.add(row);
      }
    }
    return examples;
  }

  private static int count(Object value) {
    return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
  }

  private static String number(Object value) {
    Double numeric = finite(value);
    return numeric == null ? "未知" : BigDecimal.valueOf(numeric).stripTrailingZeros().toPlainString();
  }

  private static String symbol(Object operator) {
    return switch (operator instanceof String text ? text : "") {
      case "gt" -> ">";
      case "gte" -> ">=";
      case "lt" -> "<";
      case "lte" -> "<=";
      case "eq" -> "=";
      case "ne" -> "!=";
      default -> "?";
    };
  }

  private static String bounded(String value, int max) {
    if (value == null) return "";
    int count = value.codePointCount(0, value.length());
    return count <= max ? value : value.substring(0, value.offsetByCodePoints(0, max));
  }
}
