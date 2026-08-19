package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class AccountVaultServiceTest {
  private static byte[] key() {
    return Arrays.copyOf("account-vault-test-key-32-bytes!".getBytes(StandardCharsets.UTF_8), 32);
  }

  @Test
  void encryptsAndDecryptsSecrets() {
    AccountVaultService.SecretCipher cipher = new AccountVaultService.SecretCipher(key());

    String encrypted = cipher.encrypt("secret-token-123");

    assertNotEquals("secret-token-123", encrypted);
    assertEquals("secret-token-123", cipher.decrypt(encrypted));
  }

  @Test
  void usesDifferentNoncesForTheSameSecret() {
    AccountVaultService.SecretCipher cipher = new AccountVaultService.SecretCipher(key());

    assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"));
  }

  @Test
  void rejectsWrongEncryptionKey() {
    AccountVaultService.SecretCipher first = new AccountVaultService.SecretCipher(key());
    byte[] otherKey = key();
    otherKey[0] ^= 1;
    AccountVaultService.SecretCipher second = new AccountVaultService.SecretCipher(otherKey);

    assertThrows(IllegalStateException.class, () -> second.decrypt(first.encrypt("secret")));
  }

  @Test
  void parsesKeywordAccountsAndAccountPairs() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet keywords = workbook.createSheet("工作表1");
      Row header = keywords.createRow(0);
      header.createCell(0).setCellValue("关键词");
      header.createCell(1).setCellValue("账户");
      header.createCell(2).setCellValue("url");
      header.createCell(3).setCellValue("channel");
      header.createCell(4).setCellValue("国家");
      Row keyword = keywords.createRow(1);
      keyword.createCell(0).setCellValue("Borrow");
      keyword.createCell(1).setCellValue("10001\n10002");
      keyword.createCell(2).setCellValue("https://example.com");
      keyword.createCell(3).setCellValue("channel-a");
      keyword.createCell(4).setCellValue("US");

      Sheet accounts = workbook.createSheet("工作表3");
      Row pair = accounts.createRow(0);
      pair.createCell(0).setCellValue("账户甲");
      pair.createCell(1).setCellValue("90001");

      List<Map<String, Object>> rows = AccountVaultService.parseWorkbook(workbook);

      assertEquals(3, rows.size());
      assertEquals("10001", rows.get(0).get("accountId"));
      assertEquals("10002", rows.get(1).get("accountId"));
      assertEquals("账户甲", rows.get(2).get("name"));
      assertEquals("90001", rows.get(2).get("accountId"));
    }
  }

  @Test
  void springCanResolveTheServiceConstructor() {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.accountVaultKey()).thenReturn(key());
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(AccountVaultRepository.class, () -> mock(AccountVaultRepository.class));
      context.registerBean(RuntimeConfig.class, () -> config);
      context.register(AccountVaultService.class);
      context.refresh();

      assertNotNull(context.getBean(AccountVaultService.class));
    }
  }
}
