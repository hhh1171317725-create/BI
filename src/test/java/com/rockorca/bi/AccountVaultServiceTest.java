package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class AccountVaultServiceTest {
  @Test
  void acceptsArticleUrlsWithAdvertisingMacros() {
    assertDoesNotThrow(() -> AccountVaultService.validateUrl(
        "https://hub.next-game.org/2783/?utm_campaign={campaign_id}"
            + "&clickid={cf_click_id}&click_id={cf_click_id}"));
  }

  @Test
  void keepsMultipleAccountsInOneKeywordMapping() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("工作表1");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("关键词");
      header.createCell(1).setCellValue("账户");
      header.createCell(2).setCellValue("url");
      header.createCell(3).setCellValue("channel");
      header.createCell(4).setCellValue("style ID");
      header.createCell(5).setCellValue("国家");
      header.createCell(6).setCellValue("素材链接");
      Row mapping = sheet.createRow(1);
      mapping.createCell(0).setCellValue("Borrow");
      mapping.createCell(1).setCellValue("10001\n10002");
      mapping.createCell(2).setCellValue("https://example.com/article");
      mapping.createCell(3).setCellValue("channel-a");
      mapping.createCell(4).setCellValue("style-1");
      mapping.createCell(5).setCellValue("美国,英国");
      mapping.createCell(6).setCellValue("https://example.com/materials");

      List<Map<String, Object>> rows = AccountVaultService.parseWorkbook(workbook);

      assertEquals(1, rows.size());
      assertEquals("Borrow", rows.getFirst().get("keyword"));
      assertEquals("10001\n10002", rows.getFirst().get("accountIds"));
      assertEquals("channel-a", rows.getFirst().get("channelId"));
      assertEquals("style-1", rows.getFirst().get("styleId"));
      assertEquals("美国,英国", rows.getFirst().get("country"));
      assertEquals("https://example.com/article", rows.getFirst().get("articleUrl"));
      assertEquals("https://example.com/materials", rows.getFirst().get("materialUrl"));
    }
  }

  @Test
  void springCanResolveTheServiceConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(AccountVaultRepository.class, () -> mock(AccountVaultRepository.class));
      context.register(AccountVaultService.class);
      context.refresh();

      assertNotNull(context.getBean(AccountVaultService.class));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void groupsSavedChannelAndStyleOptions() {
    AccountVaultRepository repository = mock(AccountVaultRepository.class);
    when(repository.listOptions()).thenReturn(List.of(
        new AccountVaultRepository.OptionEntry(1, "channel", "channel-a"),
        new AccountVaultRepository.OptionEntry(2, "style_id", "1751233588")));
    when(repository.listUsageEntries()).thenReturn(List.of(
        storedEntry(9, "Existing", "10001", "channel-a")));
    AccountVaultService service = new AccountVaultService(repository);

    Map<String, Object> result = service.options();
    List<Map<String, Object>> channels = (List<Map<String, Object>>) result.get("channels");
    List<Map<String, Object>> styleIds = (List<Map<String, Object>>) result.get("styleIds");

    assertEquals("channel-a", channels.getFirst().get("value"));
    assertEquals(true, channels.getFirst().get("used"));
    assertEquals(List.of("Existing"), channels.getFirst().get("usedBy"));
    assertEquals("1751233588", styleIds.getFirst().get("value"));
  }

  @Test
  void rejectsUnknownOptionTypes() {
    AccountVaultService service = new AccountVaultService(mock(AccountVaultRepository.class));

    assertThrows(IllegalArgumentException.class,
        () -> service.createOption(Map.of("type", "other", "value", "x")));
  }

  @Test
  void normalizesMultipleCountries() {
    AccountVaultRepository repository = mock(AccountVaultRepository.class);
    when(repository.listUsageEntries()).thenReturn(List.of());
    when(repository.create(any())).thenAnswer(call -> call.getArgument(0));
    AccountVaultService service = new AccountVaultService(repository);

    Map<String, Object> result = service.create(Map.of(
        "keyword", "Borrow",
        "accountIds", "10001",
        "country", List.of("美国", "英国", "美国"),
        "channelId", "channel-a",
        "styleId", "style-1",
        "articleUrl", "https://example.com/article",
        "materialUrl", "https://example.com/materials"), 1);

    assertEquals("美国,英国", result.get("country"));
  }

  @Test
  void rejectsAccountsAndChannelsUsedByAnotherKeyword() {
    AccountVaultRepository repository = mock(AccountVaultRepository.class);
    when(repository.listUsageEntries()).thenReturn(List.of(
        storedEntry(9, "Existing", "10001\n10002", "channel-a")));
    AccountVaultService service = new AccountVaultService(repository);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> service.create(Map.of(
            "keyword", "New keyword",
            "accountIds", "10002\n10003",
            "country", "美国",
            "channelId", "channel-a",
            "styleId", "style-2",
            "articleUrl", "https://example.com/article"), 1));

    assertTrue(error.getMessage().contains("账户 ID “10002”"));
    assertTrue(error.getMessage().contains("channel “channel-a”"));
    assertTrue(error.getMessage().contains("Existing"));
  }

  private static AccountVaultRepository.Entry storedEntry(
      long id, String keyword, String accountIds, String channel) {
    return new AccountVaultRepository.Entry(
        id, "ad_account", keyword, accountIds, "", "", "https://example.com/article", "",
        keyword, channel, "style-1", "美国", "", "", 1, 1, null, null);
  }
}
