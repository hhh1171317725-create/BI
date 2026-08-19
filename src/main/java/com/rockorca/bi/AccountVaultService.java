package com.rockorca.bi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class AccountVaultService {
  private static final List<String> CATEGORIES = List.of("ad_account", "platform_login", "link_resource");
  private static final List<String> EXPORT_HEADERS = List.of(
      "分类", "名称", "账户ID", "用户名", "密码或Token", "网址", "关键词", "channel",
      "style ID", "国家", "负责人", "备注");
  private final AccountVaultRepository repository;
  private final SecretCipher cipher;

  public AccountVaultService(AccountVaultRepository repository, RuntimeConfig config) {
    this.repository = repository;
    this.cipher = new SecretCipher(config.accountVaultKey());
  }

  AccountVaultService(AccountVaultRepository repository, byte[] key) {
    this.repository = repository;
    this.cipher = new SecretCipher(key);
  }

  public Map<String, Object> list(String queryValue, String categoryValue, int pageValue, int pageSizeValue) {
    String query = clean(queryValue, 200, "搜索词");
    String category = categoryValue == null || categoryValue.isBlank() ? "" : category(categoryValue);
    int page = Math.max(1, pageValue);
    int pageSize = Math.max(10, Math.min(100, pageSizeValue));
    AccountVaultRepository.Page result = repository.list(query, category, page, pageSize);
    return ReportService.mapOf(
        "entries", result.entries().stream().map(this::view).toList(),
        "total", result.total(), "page", page, "pageSize", pageSize,
        "pages", Math.max(1, (result.total() + pageSize - 1) / pageSize));
  }

  public Map<String, Object> create(Map<String, Object> payload, long actorId) {
    AccountVaultRepository.Entry entry = entry(payload, actorId, actorId, "");
    return view(repository.create(entry));
  }

  public Map<String, Object> update(long id, Map<String, Object> payload, long actorId) {
    AccountVaultRepository.Entry existing = repository.find(id);
    AccountVaultRepository.Entry entry = entry(
        payload, existing.createdBy(), actorId, existing.secretEncrypted());
    return view(repository.update(id, entry));
  }

  public void delete(long id) {
    repository.delete(id);
  }

  public Map<String, Object> reveal(long id) {
    AccountVaultRepository.Entry entry = repository.find(id);
    return ReportService.mapOf("id", id, "secret", cipher.decrypt(entry.secretEncrypted()));
  }

  public int importWorkbook(byte[] content, long actorId) {
    if (content == null || content.length == 0) throw new IllegalArgumentException("请选择 Excel 文件");
    if (content.length > 10_000_000) throw new IllegalArgumentException("Excel 文件不能超过 10MB");
    List<Map<String, Object>> rows;
    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      rows = parseWorkbook(workbook);
    } catch (Exception error) {
      throw new IllegalArgumentException("读取 Excel 失败：" + error.getMessage(), error);
    }
    if (rows.isEmpty()) throw new IllegalArgumentException("Excel 中没有可导入的账户资料");
    if (rows.size() > 2_000) throw new IllegalArgumentException("单次最多导入 2000 条资料");
    List<AccountVaultRepository.Entry> entries = rows.stream()
        .map(row -> entry(row, actorId, actorId, ""))
        .toList();
    repository.createAll(entries);
    return entries.size();
  }

  public byte[] exportWorkbook() {
    List<AccountVaultRepository.Entry> entries = repository.list("", "", 1, 10_000).entries();
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("账户资料");
      Row header = sheet.createRow(0);
      CellStyle headerStyle = workbook.createCellStyle();
      headerStyle.setFillForegroundColor((short) 44);
      headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      Font headerFont = workbook.createFont();
      headerFont.setBold(true);
      headerStyle.setFont(headerFont);
      for (int index = 0; index < EXPORT_HEADERS.size(); index++) {
        Cell cell = header.createCell(index);
        cell.setCellValue(EXPORT_HEADERS.get(index));
        cell.setCellStyle(headerStyle);
      }
      int rowIndex = 1;
      for (AccountVaultRepository.Entry entry : entries) {
        Row row = sheet.createRow(rowIndex++);
        List<String> values = List.of(
            categoryLabel(entry.category()), entry.name(), entry.accountId(), entry.username(),
            cipher.decrypt(entry.secretEncrypted()), entry.url(), entry.keyword(), entry.channelId(),
            entry.styleId(), entry.country(), entry.owner(), entry.notes());
        for (int index = 0; index < values.size(); index++) row.createCell(index).setCellValue(values.get(index));
      }
      int[] widths = {14, 28, 24, 28, 28, 60, 28, 18, 18, 16, 16, 40};
      for (int index = 0; index < widths.length; index++) sheet.setColumnWidth(index, widths[index] * 256);
      sheet.createFreezePane(0, 1);
      sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, 11));
      workbook.write(output);
      return output.toByteArray();
    } catch (Exception error) {
      throw new IllegalStateException("导出账户资料失败：" + error.getMessage(), error);
    }
  }

  private AccountVaultRepository.Entry entry(
      Map<String, Object> payload, long createdBy, long updatedBy, String existingEncrypted) {
    String category = category(text(payload.get("category")));
    String name = clean(text(payload.get("name")), 255, "名称");
    if (name.isBlank()) throw new IllegalArgumentException("请填写名称");
    String secret = text(payload.get("secret"));
    String encrypted = secret.isBlank() && !existingEncrypted.isBlank()
        ? existingEncrypted : cipher.encrypt(secretValue(secret));
    String url = clean(text(payload.get("url")), 2000, "网址");
    validateUrl(url);
    return new AccountVaultRepository.Entry(
        0, category, name,
        clean(text(payload.get("accountId")), 255, "账户 ID"),
        clean(text(payload.get("username")), 500, "用户名"), encrypted, url,
        clean(text(payload.get("keyword")), 1000, "关键词"),
        clean(text(payload.get("channelId")), 255, "channel"),
        clean(text(payload.get("styleId")), 255, "style ID"),
        clean(text(payload.get("country")), 255, "国家"),
        clean(text(payload.get("owner")), 255, "负责人"),
        clean(text(payload.get("notes")), 5000, "备注"),
        createdBy, updatedBy, null, null);
  }

  private Map<String, Object> view(AccountVaultRepository.Entry entry) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", entry.id());
    result.put("category", entry.category());
    result.put("categoryLabel", categoryLabel(entry.category()));
    result.put("name", entry.name());
    result.put("accountId", entry.accountId());
    result.put("username", entry.username());
    String secret = cipher.decrypt(entry.secretEncrypted());
    result.put("secretConfigured", !secret.isBlank());
    result.put("secretMasked", secret.isBlank() ? "" : "••••••••");
    result.put("url", entry.url());
    result.put("keyword", entry.keyword());
    result.put("channelId", entry.channelId());
    result.put("styleId", entry.styleId());
    result.put("country", entry.country());
    result.put("owner", entry.owner());
    result.put("notes", entry.notes());
    result.put("updatedAt", entry.updatedAt() == null ? "" : entry.updatedAt().toString());
    return result;
  }

  static List<Map<String, Object>> parseWorkbook(Workbook workbook) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Sheet sheet : workbook) {
      if (sheet.getPhysicalNumberOfRows() == 0) continue;
      Map<String, Integer> headers = headers(sheet.getRow(sheet.getFirstRowNum()));
      if (headers.containsKey("名称") || headers.containsKey("分类")) {
        parseStandard(sheet, headers, rows);
      } else if (headers.containsKey("关键词") && headers.containsKey("账户")) {
        parseKeywordAccounts(sheet, headers, rows);
      } else if (sheet.getLastRowNum() >= 10 && "工作表2".equals(sheet.getSheetName())) {
        parseResourceSheet(sheet, rows);
      } else if (sheet.getLastRowNum() >= 0 && sheet.getRow(0) != null
          && !cell(sheet.getRow(0), 0).isBlank() && !cell(sheet.getRow(0), 1).isBlank()) {
        parseAccountPairs(sheet, rows);
      }
    }
    return rows;
  }

  private static void parseStandard(Sheet sheet, Map<String, Integer> headers, List<Map<String, Object>> out) {
    for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) continue;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("category", categoryCode(value(row, headers, "分类")));
      item.put("name", value(row, headers, "名称"));
      item.put("accountId", value(row, headers, "账户ID"));
      item.put("username", value(row, headers, "用户名"));
      item.put("secret", value(row, headers, "密码或Token"));
      item.put("url", value(row, headers, "网址"));
      item.put("keyword", value(row, headers, "关键词"));
      item.put("channelId", value(row, headers, "channel"));
      item.put("styleId", value(row, headers, "style ID"));
      item.put("country", value(row, headers, "国家"));
      item.put("owner", value(row, headers, "负责人"));
      item.put("notes", value(row, headers, "备注"));
      if (!text(item.get("name")).isBlank()) out.add(item);
    }
  }

  private static void parseKeywordAccounts(Sheet sheet, Map<String, Integer> headers, List<Map<String, Object>> out) {
    for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) continue;
      String keyword = value(row, headers, "关键词");
      String accounts = value(row, headers, "账户");
      for (String account : accounts.split("\\R")) {
        if (account.isBlank()) continue;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("category", "ad_account");
        item.put("name", keyword.isBlank() ? account.trim() : keyword);
        item.put("accountId", account.trim());
        item.put("keyword", keyword);
        item.put("url", value(row, headers, "url"));
        item.put("channelId", value(row, headers, "channel"));
        item.put("country", value(row, headers, "国家"));
        out.add(item);
      }
    }
  }

  private static void parseResourceSheet(Sheet sheet, List<Map<String, Object>> out) {
    for (int rowIndex = 11; rowIndex <= Math.min(sheet.getLastRowNum(), 23); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) continue;
      String name = cell(row, 0);
      String username = cell(row, 1);
      if (name.isBlank() || username.isBlank()) continue;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("category", "platform_login");
      item.put("name", name);
      item.put("username", username);
      item.put("secret", cell(row, 2));
      out.add(item);
    }
  }

  private static void parseAccountPairs(Sheet sheet, List<Map<String, Object>> out) {
    for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) continue;
      String name = cell(row, 0);
      String account = cell(row, 1);
      if (name.isBlank() || account.isBlank()) continue;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("category", "ad_account");
      item.put("name", name);
      item.put("accountId", account);
      item.put("owner", cell(row, 2));
      out.add(item);
    }
  }

  private static Map<String, Integer> headers(Row row) {
    Map<String, Integer> headers = new LinkedHashMap<>();
    if (row == null) return headers;
    for (Cell cell : row) {
      String value = cell(cell).trim();
      if (!value.isBlank()) headers.put(value, cell.getColumnIndex());
    }
    return headers;
  }

  private static String value(Row row, Map<String, Integer> headers, String name) {
    Integer index = headers.get(name);
    return index == null ? "" : cell(row, index);
  }

  private static String cell(Row row, int index) {
    return row == null ? "" : cell(row.getCell(index));
  }

  private static String cell(Cell cell) {
    return cell == null ? "" : new DataFormatter().formatCellValue(cell).trim();
  }

  private static String category(String value) {
    String result = categoryCode(value);
    if (!CATEGORIES.contains(result)) throw new IllegalArgumentException("资料分类无效");
    return result;
  }

  private static String categoryCode(String value) {
    return switch (String.valueOf(value).trim()) {
      case "投放账户", "ad_account", "" -> "ad_account";
      case "平台账号", "platform_login" -> "platform_login";
      case "链接资源", "link_resource" -> "link_resource";
      default -> String.valueOf(value).trim();
    };
  }

  private static String categoryLabel(String value) {
    return switch (value) {
      case "platform_login" -> "平台账号";
      case "link_resource" -> "链接资源";
      default -> "投放账户";
    };
  }

  private static String clean(String value, int max, String label) {
    String result = String.valueOf(value == null ? "" : value).trim();
    if (result.length() > max || result.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(label + "长度或格式无效");
    }
    return result;
  }

  private static String secretValue(String value) {
    String result = String.valueOf(value == null ? "" : value);
    if (result.length() > 5000 || result.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("密码或 Token 格式无效");
    }
    return result;
  }

  private static void validateUrl(String value) {
    if (value.isBlank()) return;
    try {
      URI uri = URI.create(value);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
        throw new IllegalArgumentException();
      }
    } catch (Exception ignored) {
      throw new IllegalArgumentException("网址必须是有效的 http 或 https 地址");
    }
  }

  private static String text(Object value) {
    return String.valueOf(value == null ? "" : value);
  }

  static final class SecretCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    SecretCipher(byte[] keyBytes) {
      if (keyBytes == null || keyBytes.length != 32) throw new IllegalArgumentException("加密密钥长度无效");
      key = new SecretKeySpec(keyBytes.clone(), "AES");
    }

    String encrypt(String value) {
      if (value == null || value.isEmpty()) return "";
      try {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] encrypted = aes.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return "v1:" + Base64.getEncoder().encodeToString(nonce) + ":"
            + Base64.getEncoder().encodeToString(encrypted);
      } catch (GeneralSecurityException error) {
        throw new IllegalStateException("加密账户资料失败", error);
      }
    }

    String decrypt(String value) {
      if (value == null || value.isBlank()) return "";
      try {
        String[] parts = value.split(":", 3);
        if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, key,
            new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
        return new String(aes.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
      } catch (Exception error) {
        throw new IllegalStateException("账户资料解密失败，请检查服务器加密密钥", error);
      }
    }
  }
}
