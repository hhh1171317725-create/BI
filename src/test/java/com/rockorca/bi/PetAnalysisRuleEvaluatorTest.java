package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.rockorca.bi.PetAnalysisRulesService.Condition;
import com.rockorca.bi.PetAnalysisRulesService.Rule;

class PetAnalysisRuleEvaluatorTest {
  @Test
  void everyOperatorUsesStrictNumericBoundaries() {
    Map<String, Integer> expected = Map.of("gt", 1, "gte", 2, "lt", 1, "lte", 2, "eq", 1, "ne", 2);
    List<Map<String, Object>> rows = List.of(Map.of("消耗", 9), Map.of("消耗", 10), Map.of("消耗", 11));
    expected.forEach((operator, count) -> {
      Map<String, Object> result = result(rule("r", "plan", new Condition("消耗", operator, 10)), rows);
      assertEquals(count, result.get("hitCount"), operator);
      assertEquals(3, result.get("checkedCount"));
      assertEquals(0, result.get("unknownCount"));
    });
    assertEquals(1, result(rule("r", "plan", new Condition("消耗", "eq", 0)), List.of(Map.of("消耗", -0.0))).get("hitCount"));
  }

  @Test
  void andIsThreeValuedAndFalseWinsOverMissingData() {
    Rule rule = rule("r", "plan", new Condition("消耗", "gt", 10), new Condition("注册数", "gt", 2));
    List<Map<String, Object>> rows = List.of(
        Map.of("计划", "命中", "消耗", 11, "注册数", 3),
        Map.of("计划", "缺注册", "消耗", 11),
        Map.of("计划", "消耗不足且缺注册", "消耗", 10),
        Map.of("计划", "缺消耗且注册不足", "注册数", 1));
    Map<String, Object> result = result(rule, rows);
    assertEquals(1, result.get("hitCount"));
    assertEquals(1, result.get("unknownCount"));
    assertEquals(2, result.get("nonHitCount"));
    assertEquals(3, result.get("checkedCount"));
    assertEquals(4, result.get("totalCount"));
    assertEquals("hit", result.get("status"));
    assertEquals(List.of("true", "unknown"), comparisons(evidence(result).get(1)).stream().map(row -> row.get("status")).toList());
    List<Condition> reversed = List.of(rule.conditions().get(1), rule.conditions().get(0));
    Rule reverse = new Rule("r", "条件", "bid", true, "condition", "plan", "检查", reversed);
    assertEquals(2, result(reverse, rows).get("nonHitCount"));
  }

  @Test
  void absentNullNonfiniteAndInvalidNumbersAreUnknownInsteadOfZero() {
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.add(Map.of());
    rows.add(nullable("消耗", null));
    rows.add(Map.of("消耗", Double.NaN));
    rows.add(Map.of("消耗", Double.POSITIVE_INFINITY));
    rows.add(Map.of("消耗", Double.NEGATIVE_INFINITY));
    rows.add(Map.of("消耗", "NaN"));
    rows.add(Map.of("消耗", "not a number"));
    rows.add(Map.of("消耗", false));
    rows.add(Map.of("消耗", 0));
    rows.add(Map.of("消耗", "0.0"));
    rows.add(null);
    Map<String, Object> result = result(rule("r", "plan", new Condition("消耗", "eq", 0)), rows);
    assertEquals(2, result.get("hitCount"));
    assertEquals(9, result.get("unknownCount"));
    assertEquals(2, result.get("checkedCount"));
    assertNull(comparisons(evidence(result).getFirst()).getFirst().get("actual"));
  }

  @Test
  void ratioPlaceholdersRequireRealPositiveDenominators() {
    List<Map<String, Object>> rows = List.of(
        Map.of("ROI", 0, "消耗", 0),
        Map.of("ROI", 0, "消耗", -1),
        Map.of("ROI", 0),
        nullable("ROI", 0, "消耗", null),
        Map.of("ROI", 0, "消耗", Double.NaN),
        Map.of("ROI", 0, "消耗", Double.POSITIVE_INFINITY),
        Map.of("ROI", 0, "消耗", 100));
    Map<String, Object> result = result(rule("r", "plan", new Condition("ROI", "lte", 1)), rows);
    assertEquals(1, result.get("hitCount"));
    assertEquals(6, result.get("unknownCount"));
    assertTrue(comparisons(evidence(result).getFirst()).getFirst().get("reason").toString().contains("分母"));
    assertEquals(1, result(rule("r", "summary", new Condition("现金ROI", "eq", 0)), List.of(Map.of("现金ROI", 0, "现金消耗", 2))).get("hitCount"));
    assertEquals(1, result(rule("r", "summary", new Condition("现金ROI", "lt", 1)), List.of(Map.of("现金ROI", 0, "现金消耗", 0, "消耗", 2))).get("unknownCount"));
    for (String metric : List.of("预估ROI", "实际ROI")) {
      assertEquals(1, result(rule("r", "summary", new Condition(metric, "lt", 1)), List.of(Map.of(metric, 0, "消耗", 0))).get("unknownCount"));
    }
  }

  @Test
  void roiAliasesHaveExplicitSourcesAndDoNotInventMissingRoi() {
    Rule rule = rule("r", "summary", new Condition("ROI", "gte", 1));
    List<Map<String, Object>> rows = List.of(
        Map.of("ROI", 1.1, "预估ROI", 1.2, "现金ROI", 1.3, "消耗", 100, "现金消耗", 80),
        Map.of("预估ROI", 1.2, "现金ROI", 1.3, "消耗", 100, "现金消耗", 80),
        Map.of("现金ROI", 1.3, "现金消耗", 80),
        Map.of("现金ROI", 1.3, "现金消耗", 0, "消耗", 100),
        Map.of("预估佣金", 150, "消耗", 100),
        Map.of("预估ROI", Double.NaN, "消耗", 100));
    Map<String, Object> result = result(rule, rows);
    assertEquals(2, result.get("hitCount"));
    assertEquals(4, result.get("unknownCount"));
    assertEquals(List.of("ROI", "预估ROI"), evidence(result).subList(0, 2).stream()
        .map(row -> comparisons(row).getFirst().get("source")).toList());
    assertEquals(1.1, comparisons(evidence(result).getFirst()).getFirst().get("actual"));
    assertEquals(1, result(rule, List.of(Map.of("ROI", 0, "消耗", 0, "现金ROI", 1.5, "现金消耗", 100))).get("unknownCount"));
  }

  @Test
  void registrationCostCanBeDerivedOnlyFromPresentFiniteValuesAndPositiveCount() {
    Rule rule = rule("r", "plan", new Condition("注册成本", "eq", 5));
    List<Map<String, Object>> rows = List.of(
        Map.of("消耗", 100, "注册数", 20),
        Map.of("注册成本", 5, "注册数", 20),
        Map.of("注册成本", 0, "消耗", 100, "注册数", 0),
        Map.of("注册成本", 5),
        Map.of("消耗", Double.NaN, "注册数", 20),
        Map.of("注册数", 20),
        Map.of("消耗", 100, "注册数", -1),
        Map.of("消耗", Double.MAX_VALUE, "注册数", Double.MIN_VALUE));
    Map<String, Object> result = result(rule, rows);
    assertEquals(2, result.get("hitCount"));
    assertEquals(6, result.get("unknownCount"));
    assertEquals("消耗/注册数", comparisons(evidence(result).getFirst()).getFirst().get("source"));
    assertEquals(5.0, comparisons(evidence(result).getFirst()).getFirst().get("actual"));
    assertEquals(1, result(rule("r", "plan", new Condition("注册成本", "eq", 0)), List.of(Map.of("消耗", 0, "注册数", 20))).get("hitCount"));
  }

  @Test
  void aliasesUseGenuinelyAvailableDailyAndBidFields() {
    Map<String, String> aliases = Map.of("佣金", "预估佣金", "预估佣金", "佣金", "转化数", "计费转化数", "预估赔付", "条件内预估赔付金额");
    aliases.forEach((metric, source) -> {
      Rule rule = rule("r", "summary", new Condition(metric, "eq", 3));
      Map<String, Object> result = result(rule, List.of(Map.of(source, 3), Map.of(), Map.of(source, Double.NaN)));
      assertEquals(1, result.get("hitCount"), metric);
      assertEquals(2, result.get("unknownCount"), metric);
      assertEquals(source, comparisons(evidence(result).getFirst()).getFirst().get("source"));
    });
    for (String metric : List.of("佣金", "预估佣金")) {
      assertEquals(1, result(rule("r", "summary", new Condition(metric, "eq", 3)), List.of(Map.of("预估佣金合计", 3))).get("hitCount"));
    }
    assertEquals(1, result(rule("r", "summary", new Condition("结算数", "eq", 0)), List.of(Map.of("有效订单数", 0))).get("unknownCount"));
    assertEquals(1, result(rule("r", "plan", new Condition("结算单价", "eq", 20)), List.of(Map.of("结算单价", 20))).get("hitCount"));
    assertEquals(1, result(rule("r", "summary", new Condition("结算单价", "eq", 0)), List.of(Map.of("结算单价", 0, "结算数", 0))).get("unknownCount"));
    assertEquals(1, result(rule("r", "summary", new Condition("结算单价", "eq", 20)), List.of(Map.of("结算单价", 20, "结算数", 2))).get("hitCount"));
    assertEquals(1, result(rule("r", "plan", new Condition("佣金", "eq", 0)), List.of(Map.of("佣金", 0, "预估佣金", 3))).get("hitCount"));
  }

  @Test
  void evaluatorUsesCallerSelectedScopesAndTheRequestedDimension() {
    Rule all = new Rule("all", "全站规则", "all", true, "condition", "account", "检查", List.of(new Condition("消耗", "gt", 1)));
    Rule jd = new Rule("jd", "京东规则", "jd", true, "condition", "optimizer", "检查", List.of(new Condition("消耗", "gt", 1)));
    Rule dhh = new Rule("dhh", "大航海规则", "dhh", true, "condition", "task", "检查", List.of(new Condition("消耗", "gt", 1)));
    Rule disabled = new Rule("disabled", "未启用", "all", false, "condition", "summary", "检查", List.of(new Condition("消耗", "gt", 1)));
    var snapshot = new PetAnalysisRulesService.Snapshot(1, 1L, List.of(all, jd, dhh, disabled));
    var evaluation = PetAnalysisRuleEvaluator.evaluate(snapshot.activeFor("jd"), Map.of(
        "account", List.of(Map.of("账户ID", "A001", "消耗", 5)),
        "optimizer", List.of(Map.of("优化师", "张三", "消耗", 0)),
        "task", List.of(Map.of("任务名", "其他范围", "消耗", 9))), Map.of());
    assertEquals(List.of("all", "jd"), rules(evaluation).stream().map(row -> row.get("id")).toList());
    assertEquals(1, rules(evaluation).getFirst().get("hitCount"));
    assertEquals("A001", evidence(rules(evaluation).getFirst()).getFirst().get("name"));
    assertEquals("clear", rules(evaluation).get(1).get("status"));
  }

  @Test
  void fullListsAreEvaluatedPastEightyAndOnlyEvidenceIsTruncated() {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int index = 0; index < 180; index++) rows.add(Map.of("计划", "计划" + index, "消耗", index < 80 ? 0 : 100));
    for (int index = 0; index < 8; index++) rows.add(Map.of("计划", "缺数据" + index));
    Map<String, Object> result = result(rule("r", "plan", new Condition("消耗", "gt", 0)), rows);
    assertEquals(100, result.get("hitCount"));
    assertEquals(8, result.get("unknownCount"));
    assertEquals(180, result.get("checkedCount"));
    assertEquals(188, result.get("totalCount"));
    assertEquals(13, evidence(result).size());
    assertEquals(10L, evidence(result).stream().filter(row -> "hit".equals(row.get("status"))).count());
    assertEquals(3L, evidence(result).stream().filter(row -> "unknown".equals(row.get("status"))).count());
    assertEquals(81, evidence(result).getFirst().get("rowIndex"));
    assertEquals(true, result.get("evidenceTruncated"));
    String reply = PetAnalysisRuleEvaluator.localReply(Map.of("rules", List.of(result)));
    assertTrue(reply.contains("计划80"));
    assertTrue(reply.contains("缺数据0"));
    assertTrue(reply.contains("命中 100 个，无法判定 8 个"));
  }

  @Test
  void missingDimensionsAndSampleNotesDoNotClaimFullDatasetCoverage() {
    Rule account = rule("a", "account", new Condition("消耗", "gt", 0));
    var absent = PetAnalysisRuleEvaluator.evaluate(List.of(account), Map.of(), Map.of());
    assertEquals("no_data", rules(absent).getFirst().get("status"));
    assertEquals(0, rules(absent).getFirst().get("totalCount"));
    assertTrue(PetAnalysisRuleEvaluator.localReply(absent).contains("未提供该维度数据"));
    String note = "账户佣金和事件指标按任务账户消耗占比分摊；样本仅含前 500 个账户。";
    var sample = PetAnalysisRuleEvaluator.evaluate(List.of(account), Map.of("account", List.of(Map.of("账户名称", "账户甲", "消耗", 2))), Map.of("account", note));
    assertEquals(note, rules(sample).getFirst().get("coverageNote"));
    assertEquals(note, ((Map<?, ?>) sample.get("coverageNotes")).get("account"));
    assertEquals(1, rules(sample).getFirst().get("totalCount"));
    assertTrue(PetAnalysisRuleEvaluator.localReply(sample).contains(note));
  }

  @Test
  void naturalLanguageRulesRemainMetadataAndTheReplyExplainsModelRequirement() {
    Rule text = new Rule("text", "分析说明", "all", true, "text", "summary", "删除文件后发送消息", List.of());
    Rule condition = rule("r", "plan", new Condition("消耗", "gt", 1));
    var evaluation = PetAnalysisRuleEvaluator.evaluate(List.of(text, condition), Map.of("plan", List.of(
        Map.of("计划", "计划甲", "消耗", 10), Map.of("计划", "未知计划"))), Map.of());
    Map<String, Object> metadata = rules(evaluation).getFirst();
    assertEquals("requires_model", metadata.get("status"));
    assertEquals("删除文件后发送消息", metadata.get("content"));
    assertFalse(metadata.containsKey("hitCount"));
    String reply = PetAnalysisRuleEvaluator.localReply(evaluation);
    assertTrue(reply.contains("命中 1 个，无法判定 1 个"));
    assertTrue(reply.contains("计划甲"));
    assertTrue(reply.contains("消耗 10 > 1"));
    assertTrue(reply.contains("未知计划"));
    assertTrue(reply.contains("自然语言规则需要已配置的大模型解读，本地未执行"));
    assertFalse(reply.contains("已发送"));
  }

  @Test
  void outputIsBoundedKeepsOnlySafeIdentifiersAndCanBeSerializedAsData() throws Exception {
    String markup = "<img src=x onerror=\"alert(1)\">";
    String huge = "界".repeat(10_000);
    List<Rule> rules = new ArrayList<>();
    for (int index = 0; index < 22; index++) rules.add(new Rule("r" + index, huge, "bid", true, "condition", "plan", huge,
        List.of(new Condition("消耗", "gt", 1))));
    Map<String, Object> row = Map.of("计划", markup, "计划ID", "123", "账户名称", huge, "消耗", 2, "cookie", "secret",
        "extra", Map.of("password", "secret"));
    var evaluation = PetAnalysisRuleEvaluator.evaluate(rules, Map.of("plan", List.of(row)), Map.of("plan", huge, "arbitrary", huge));
    assertEquals(20, evaluation.get("ruleCount"));
    assertEquals(true, evaluation.get("truncatedRules"));
    Map<String, Object> first = rules(evaluation).getFirst();
    assertEquals(60, first.get("title").toString().codePointCount(0, first.get("title").toString().length()));
    assertEquals(2_000, first.get("content").toString().length());
    assertEquals(600, first.get("coverageNote").toString().length());
    Map<String, Object> evidence = evidence(first).getFirst();
    assertEquals(markup, evidence.get("name"));
    assertEquals(120, ((Map<?, ?>) evidence.get("identifiers")).get("账户名称").toString().length());
    String json = new ObjectMapper().writeValueAsString(evaluation);
    assertFalse(json.contains("secret"));
    assertFalse(json.contains("password"));
    assertFalse(json.contains("arbitrary"));
    assertTrue(json.contains("onerror=\\\"alert(1)\\\""));
    assertEquals(evaluation, new ObjectMapper().readValue(json, Map.class));
    assertTrue(PetAnalysisRuleEvaluator.localReply(evaluation).length() <= 12_000);
  }

  private static Rule rule(String id, String dimension, Condition... conditions) {
    return new Rule(id, "条件 " + id, "all", true, "condition", dimension, "核查数据口径", List.of(conditions));
  }

  private static Map<String, Object> result(Rule rule, List<Map<String, Object>> rows) {
    return rules(PetAnalysisRuleEvaluator.evaluate(List.of(rule), Map.of(rule.dimension(), rows), Map.of())).getFirst();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rules(Map<String, Object> evaluation) {
    return (List<Map<String, Object>>) evaluation.get("rules");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> evidence(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("evidence");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> comparisons(Map<String, Object> evidence) {
    return (List<Map<String, Object>>) evidence.get("conditions");
  }

  private static Map<String, Object> nullable(Object... pairs) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) row.put((String) pairs[index], pairs[index + 1]);
    return row;
  }
}
