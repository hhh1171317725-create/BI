package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class PetAnalysisRulesServiceTest {
  @TempDir Path directory;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void initialSnapshotIsEmptyAndSavedChineseRulesSurviveRestartInOrder() throws Exception {
    PetAnalysisRulesService service = service();
    assertEquals(new PetAnalysisRulesService.Snapshot(0, null, List.of()), service.snapshot());
    var disabled = textRule("bid", false, "暂停规则", "必须计入保存内容");
    var global = textRule("all", true, "全站规则", "先说明现金利润，保留\n第二行。");
    var jd = conditionRule("jd", "task", "佣金", "gte", 12.5);
    var dhh = textRule("dhh", true, "大航海", "检查注册成本");
    var saved = service.save(0, List.of(disabled, global, jd, dhh));

    assertEquals(1, saved.version());
    assertNotNull(saved.updatedAt());
    assertEquals(List.of(global, jd), saved.activeFor("jd"));
    assertEquals(List.of(global, dhh), service.activeFor("dhh"));
    assertEquals(List.of(global), service.activeFor("bid"));
    assertEquals(saved, service().snapshot());
    String json = Files.readString(file(), StandardCharsets.UTF_8);
    assertTrue(json.contains("先说明现金利润"));
    if (Files.getFileStore(file()).supportsFileAttributeView("posix")) {
      assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
          Files.getPosixFilePermissions(file()));
    }
  }

  @Test
  void snapshotsAndConditionListsCannotBeMutatedAndReadOnlyLoadsOnce() throws Exception {
    var conditions = new ArrayList<>(List.of(new PetAnalysisRulesService.Condition("消耗", "gt", 100)));
    var condition = new PetAnalysisRulesService.Rule(UUID.randomUUID().toString(), "规则", "all", true,
        "condition", "summary", "检查原因", conditions);
    conditions.clear();
    var input = new ArrayList<>(List.of(condition));
    var service = service();
    var saved = service.save(0, input);
    input.clear();
    assertEquals(1, saved.rules().size());
    assertEquals(1, saved.rules().getFirst().conditions().size());
    assertThrows(UnsupportedOperationException.class, () -> saved.rules().clear());
    assertThrows(UnsupportedOperationException.class, () -> saved.rules().getFirst().conditions().clear());
    Files.writeString(file(), "external change", StandardCharsets.UTF_8);
    assertSame(saved, service.snapshot());
  }

  @Test
  void trimsUnicodeWhitespaceAndBoundsTitlesAndContentByCharacters() {
    var service = service();
    var rule = textRule("all", true, "\u3000 标题\u00a0", "\n\u3000中文要求\t ");
    var saved = service.save(0, List.of(rule)).rules().getFirst();
    assertEquals("标题", saved.title());
    assertEquals("中文要求", saved.content());
    service.save(1, List.of(textRule("all", true, "中".repeat(60), "文".repeat(2_000))));
    assertInvalid(List.of(textRule("all", true, "\u3000\u00a0\t", "内容")));
    assertInvalid(List.of(textRule("all", true, "中".repeat(61), "内容")));
    assertInvalid(List.of(textRule("all", true, "标题", "\u3000\u00a0\n")));
    assertInvalid(List.of(textRule("all", true, "标题", "文".repeat(2_001))));
  }

  @Test
  void totalContentLimitIncludesDisabledRulesAndRuleLimitIsTwenty() {
    List<PetAnalysisRulesService.Rule> rules = new ArrayList<>();
    for (int i = 0; i < 6; i++) rules.add(textRule("all", false, "规则", "文".repeat(2_000)));
    service().save(0, rules);
    rules.add(textRule("all", false, "规则", "文"));
    assertInvalid(rules);
    rules.clear();
    for (int i = 0; i < 21; i++) rules.add(textRule("all", true, "规则", "内容"));
    assertInvalid(rules);
  }

  @Test
  void rejectsInvalidAndDuplicateIdsScopesTypesDimensionsAndConditions() {
    var valid = textRule("all", true, "规则", "内容");
    var duplicate = new PetAnalysisRulesService.Rule(valid.id().toUpperCase(), valid.title(), valid.scope(),
        valid.enabled(), valid.type(), valid.dimension(), valid.content(), List.of());
    assertInvalid(List.of(valid, duplicate));
    assertInvalid(List.of(new PetAnalysisRulesService.Rule("1-1-1-1-1", "规则", "all", true,
        "text", "summary", "内容", List.of())));
    assertInvalid(List.of(textRule("other", true, "规则", "内容")));
    assertInvalid(List.of(new PetAnalysisRulesService.Rule(valid.id(), "规则", "all", true,
        "other", "summary", "内容", List.of())));
    assertInvalid(List.of(conditionRule("all", "other", "消耗", "gt", 1)));
    assertInvalid(List.of(conditionRule("all", "account", "unknown", "gt", 1)));
    assertInvalid(List.of(conditionRule("all", "account", "消耗", "greater", 1)));
    assertInvalid(List.of(conditionRule("all", "account", "消耗", "gt", Double.NaN)));
    assertInvalid(List.of(conditionRule("all", "account", "消耗", "gt", Double.POSITIVE_INFINITY)));
    assertInvalid(List.of(new PetAnalysisRulesService.Rule(valid.id(), "规则", "all", true,
        "condition", "account", "内容", List.of())));
    assertInvalid(List.of(new PetAnalysisRulesService.Rule(valid.id(), "规则", "all", true,
        "text", "account", "内容", List.of())));
    assertInvalid(List.of(new PetAnalysisRulesService.Rule(valid.id(), "规则", "all", true,
        "text", "summary", "内容", List.of(new PetAnalysisRulesService.Condition("消耗", "gt", 1)))));
    assertInvalid(List.of(new PetAnalysisRulesService.Rule(valid.id(), "规则", "all", true,
        "condition", "account", "内容", java.util.Collections.nCopies(6,
            new PetAnalysisRulesService.Condition("消耗", "gt", 1)))));
  }

  @Test
  void staleVersionsAndConcurrentSavesCannotOverwriteTheWinner() throws Exception {
    var service = service();
    var first = textRule("all", true, "首条", "原内容");
    var saved = service.save(0, List.of(first));
    byte[] bytes = Files.readAllBytes(file());
    var stale = assertThrows(ResponseStatusException.class,
        () -> service.save(0, List.of(textRule("all", true, "旧页面", "新内容"))));
    assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
    assertSame(saved, service.snapshot());
    assertArrayEquals(bytes, Files.readAllBytes(file()));

    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures = List.of(executor.submit(() -> saveTogether(service, start)),
          executor.submit(() -> saveTogether(service, start)));
      start.countDown();
      int success = 0;
      int conflict = 0;
      for (var future : futures) {
        if (future.get() == 200) success++; else conflict++;
      }
      assertEquals(1, success);
      assertEquals(1, conflict);
    }
    assertEquals(2, service.snapshot().version());
  }

  @Test
  void failedAtomicReplacementPreservesTheLastFileAndMemoryAndCleansTemporaryFile() throws Exception {
    RuntimeConfig config = config();
    class FailingService extends PetAnalysisRulesService {
      boolean fail;
      FailingService() { super(config, mapper); }
      @Override void replace(Path temporary, Path destination) throws IOException {
        if (fail) throw new IOException("simulated file system failure");
        super.replace(temporary, destination);
      }
    }
    var service = new FailingService();
    var saved = service.save(0, List.of(textRule("all", true, "原规则", "原内容")));
    byte[] original = Files.readAllBytes(file());
    service.fail = true;
    var error = assertThrows(ResponseStatusException.class,
        () -> service.save(1, List.of(textRule("all", true, "新规则", "新内容"))));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    assertSame(saved, service.snapshot());
    assertArrayEquals(original, Files.readAllBytes(file()));
    assertEquals(saved, service().snapshot());
    try (var entries = Files.list(directory)) {
      assertEquals(List.of(file()), entries.toList());
    }
  }

  @Test
  void corruptAndInvalidSavedFilesStopReadsAndSavesWithoutOverwriting() throws Exception {
    for (String corrupt : List.of("{broken", "{}", "{\"version\":1,\"updatedAt\":1,\"rules\":[{}]}",
        "{\"version\":-1,\"updatedAt\":1,\"rules\":[]}",
        "{\"version\":0,\"updatedAt\":1,\"rules\":[]}")) {
      Files.writeString(file(), corrupt, StandardCharsets.UTF_8);
      var service = service();
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
          assertThrows(ResponseStatusException.class, service::snapshot).getStatusCode());
      assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
          () -> service.save(0, List.of())).getStatusCode());
      assertEquals(corrupt, Files.readString(file(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void unreadablePathDoesNotBecomeAnEmptySnapshotAndVersionCannotOverflow() throws Exception {
    Files.createDirectory(file());
    var unreadable = service();
    assertThrows(ResponseStatusException.class, unreadable::snapshot);
    assertThrows(ResponseStatusException.class, () -> unreadable.save(0, List.of()));
    assertTrue(Files.isDirectory(file()));
    Files.delete(file());
    String maximum = "{\"version\":9223372036854775807,\"updatedAt\":1,\"rules\":[]}";
    Files.writeString(file(), maximum, StandardCharsets.UTF_8);
    var service = service();
    assertEquals(Long.MAX_VALUE, service.snapshot().version());
    assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
        () -> service.save(Long.MAX_VALUE, List.of())).getStatusCode());
    assertEquals(maximum, Files.readString(file(), StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class, () -> service.save(-1, List.of()));
  }

  private int saveTogether(PetAnalysisRulesService service, CountDownLatch start) throws InterruptedException {
    start.await();
    try {
      service.save(1, List.of(textRule("all", true, "新规则", "新内容")));
      return 200;
    } catch (ResponseStatusException error) {
      assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
      return 409;
    }
  }

  private void assertInvalid(List<PetAnalysisRulesService.Rule> rules) {
    assertThrows(IllegalArgumentException.class, () -> service().save(0, rules));
  }

  private PetAnalysisRulesService service() { return new PetAnalysisRulesService(config(), mapper); }
  private RuntimeConfig config() {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.runtimeDir()).thenReturn(directory);
    return config;
  }
  private Path file() { return directory.resolve("pet-analysis-rules.json"); }

  static PetAnalysisRulesService.Rule textRule(String scope, boolean enabled, String title, String content) {
    return new PetAnalysisRulesService.Rule(UUID.randomUUID().toString(), title, scope, enabled,
        "text", "summary", content, List.of());
  }

  static PetAnalysisRulesService.Rule conditionRule(String scope, String dimension, String metric, String operator, double value) {
    return new PetAnalysisRulesService.Rule(UUID.randomUUID().toString(), "条件规则", scope, true,
        "condition", dimension, "解释命中情况", List.of(new PetAnalysisRulesService.Condition(metric, operator, value)));
  }
}
