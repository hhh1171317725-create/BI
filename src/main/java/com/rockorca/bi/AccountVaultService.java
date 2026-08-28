package com.rockorca.bi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class AccountVaultService {
  private static final List<String> EXPORT_HEADERS =
      List.of("关键词", "账户ID", "投放国家", "channel", "style ID", "文章链接", "素材链接", "文案",
          "收益活动ID", "推广系列数量", "日预算");
  private static final int DEFAULT_CAMPAIGN_QUANTITY = 1;
  private static final BigDecimal DEFAULT_DAILY_BUDGET = new BigDecimal("20.00");
  private final AccountVaultRepository repository;

  public AccountVaultService(AccountVaultRepository repository) {
    this.repository = repository;
  }

  public Map<String, Object> list(String queryValue, int pageValue, int pageSizeValue) {
    return list(queryValue, pageValue, pageSizeValue, null);
  }

  public Map<String, Object> list(
      String queryValue, int pageValue, int pageSizeValue, Long ownerUserId) {
    String query = clean(queryValue, 200, "搜索词");
    int page = Math.max(1, pageValue);
    // 账户对应关系页面会一次读取匹配结果后，结合实时账户指标在浏览器中做全局排序。
    // 上限与 Excel 导出量级保持一致，避免只对当前数据库分页进行排序。
    int pageSize = Math.max(10, Math.min(10_000, pageSizeValue));
    AccountVaultRepository.Page result = ownerUserId == null
        ? repository.list(query, "ad_account", page, pageSize)
        : repository.list(query, "ad_account", page, pageSize, ownerUserId);
    return ReportService.mapOf(
        "entries", result.entries().stream().map(AccountVaultService::view).toList(),
        "total", result.total(), "page", page, "pageSize", pageSize,
        "pages", Math.max(1, (result.total() + pageSize - 1) / pageSize));
  }

  public synchronized Map<String, Object> create(Map<String, Object> payload, long actorId) {
    AccountVaultRepository.Entry entry = entry(payload, actorId, actorId);
    ensureUnused(entry, 0, repository.listUsageEntries());
    ensureOptions(entry);
    return view(repository.create(entry));
  }

  public synchronized Map<String, Object> update(long id, Map<String, Object> payload, long actorId) {
    AccountVaultRepository.Entry existing = repository.find(id);
    AccountVaultRepository.Entry entry = entry(payload, existing.createdBy(), actorId);
    ensureUnused(entry, id, repository.listUsageEntries());
    ensureOptions(entry);
    return view(repository.update(id, entry));
  }

  public void delete(long id) {
    repository.delete(id);
  }

  public boolean ownedBy(long entryId, long userId) {
    return repository.find(entryId).createdBy() == userId;
  }

  public Set<String> ownedAccountIds(long userId) {
    Set<String> ids = new LinkedHashSet<>();
    for (AccountVaultRepository.Entry entry
        : repository.list("", "ad_account", 1, 10_000, userId).entries()) {
      ids.addAll(accountLines(entry.accountId()));
    }
    return ids;
  }

  public Set<String> ownedRevenueSourceIds(long userId) {
    Set<String> ids = new LinkedHashSet<>();
    for (AccountVaultRepository.Entry entry
        : repository.list("", "ad_account", 1, 10_000, userId).entries()) {
      for (String id : entry.revenueSourceIds().split("[\\r\\n,，;；]+")) {
        if (!id.isBlank()) ids.add(id.trim());
      }
    }
    return ids;
  }

  public Map<String, Object> options(Long ownerUserId) {
    List<AccountVaultRepository.OptionEntry> options = repository.listOptions(ownerUserId);
    Map<String, List<String>> channelUsage = new LinkedHashMap<>();
    List<AccountVaultRepository.Entry> usageEntries = ownerUserId == null
        ? repository.listUsageEntries()
        : repository.list("", "ad_account", 1, 10_000, ownerUserId).entries();
    for (AccountVaultRepository.Entry entry : usageEntries) {
      String keyword = entry.keyword().isBlank() ? entry.name() : entry.keyword();
      channelUsage.computeIfAbsent(entry.channelId().toLowerCase(), ignored -> new ArrayList<>())
          .add(keyword);
    }
    return ReportService.mapOf(
        "channels", options.stream().filter(option -> "channel".equals(option.type()))
            .map(option -> channelOptionView(option, channelUsage)).toList(),
        "styleIds", options.stream().filter(option -> "style_id".equals(option.type()))
            .map(AccountVaultService::optionView).toList());
  }

  public Map<String, Object> options() {
    return options(null);
  }

  public Map<String, Object> createOption(Map<String, Object> payload, long actorId) {
    String type = optionType(text(payload.get("type")));
    String value = clean(text(payload.get("value")), 255, optionLabel(type));
    if (value.isBlank()) throw new IllegalArgumentException("请填写" + optionLabel(type));
    return optionView(repository.createOption(type, value, actorId));
  }

  public void deleteOption(long id) {
    repository.deleteOption(id);
  }

  public boolean optionOwnedBy(long id, long userId) {
    return repository.optionOwnedBy(id, userId);
  }

  public synchronized int importWorkbook(byte[] content, long actorId) {
    if (content == null || content.length == 0) throw new IllegalArgumentException("请选择 Excel 文件");
    if (content.length > 10_000_000) throw new IllegalArgumentException("Excel 文件不能超过 10MB");
    List<Map<String, Object>> rows;
    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      rows = parseWorkbook(workbook);
    } catch (Exception error) {
      throw new IllegalArgumentException("读取 Excel 失败：" + error.getMessage(), error);
    }
    if (rows.isEmpty()) throw new IllegalArgumentException("Excel 中没有可导入的关键词账户映射");
    if (rows.size() > 2_000) throw new IllegalArgumentException("单次最多导入 2000 条映射");
    List<AccountVaultRepository.Entry> entries = rows.stream()
        .map(row -> entry(row, actorId, actorId))
        .toList();
    List<AccountVaultRepository.Entry> used = new ArrayList<>(repository.listUsageEntries());
    for (AccountVaultRepository.Entry entry : entries) {
      ensureUnused(entry, 0, used);
      used.add(entry);
    }
    entries.forEach(this::ensureOptions);
    repository.createAll(entries);
    return entries.size();
  }

  public byte[] exportWorkbook() {
    return exportWorkbook(null);
  }

  public byte[] exportWorkbook(Long ownerUserId) {
    List<AccountVaultRepository.Entry> entries =
        ownerUserId == null
            ? repository.list("", "ad_account", 1, 10_000).entries()
            : repository.list("", "ad_account", 1, 10_000, ownerUserId).entries();
    try (XSSFWorkbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("关键词账户映射");
      Row header = sheet.createRow(0);
      CellStyle headerStyle = workbook.createCellStyle();
      headerStyle.setFillForegroundColor((short) 44);
      headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      Font headerFont = workbook.createFont();
      headerFont.setBold(true);
      headerStyle.setFont(headerFont);
      CellStyle wrapped = workbook.createCellStyle();
      wrapped.setWrapText(true);
      for (int index = 0; index < EXPORT_HEADERS.size(); index++) {
        Cell cell = header.createCell(index);
        cell.setCellValue(EXPORT_HEADERS.get(index));
        cell.setCellStyle(headerStyle);
      }
      int rowIndex = 1;
      for (AccountVaultRepository.Entry entry : entries) {
        Row row = sheet.createRow(rowIndex++);
        List<String> values = List.of(
            entry.keyword(), entry.accountId(), entry.country(), entry.channelId(),
            entry.styleId(), entry.url(), entry.materialUrl(), entry.copyText(),
            entry.revenueSourceIds(),
            String.valueOf(entry.campaignQuantity()), decimalText(entry.dailyBudget()));
        for (int index = 0; index < values.size(); index++) {
          Cell cell = row.createCell(index);
          cell.setCellValue(values.get(index));
          if (index == 1) cell.setCellStyle(wrapped);
        }
      }
      int[] widths = {28, 30, 18, 20, 20, 70, 70, 60, 40, 16, 14};
      for (int index = 0; index < widths.length; index++) sheet.setColumnWidth(index, widths[index] * 256);
      sheet.createFreezePane(0, 1);
      sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
          0, Math.max(0, rowIndex - 1), 0, EXPORT_HEADERS.size() - 1));
      workbook.write(output);
      return output.toByteArray();
    } catch (Exception error) {
      throw new IllegalStateException("导出关键词账户映射失败：" + error.getMessage(), error);
    }
  }

  private static AccountVaultRepository.Entry entry(
      Map<String, Object> payload, long createdBy, long updatedBy) {
    String keyword = clean(text(payload.get("keyword")), 1000, "关键词");
    if (keyword.isBlank()) throw new IllegalArgumentException("请填写关键词");
    String accountIds = normalizeAccounts(text(payload.get("accountIds")));
    if (accountIds.isBlank()) throw new IllegalArgumentException("请至少填写一个账户 ID");
    String country = normalizeCountries(payload.get("country"));
    if (country.isBlank()) throw new IllegalArgumentException("请填写投放国家");
    String channel = clean(text(payload.get("channelId")), 255, "channel");
    if (channel.isBlank()) throw new IllegalArgumentException("请填写 channel");
    String styleId = clean(text(payload.get("styleId")), 255, "style ID");
    if (styleId.isBlank()) throw new IllegalArgumentException("请填写 style ID");
    String articleUrl = clean(text(payload.get("articleUrl")), 2000, "文章链接");
    validateUrl(articleUrl);
    if (articleUrl.isBlank()) throw new IllegalArgumentException("请填写文章链接");
    String materialUrl = clean(text(payload.get("materialUrl")), 2000, "素材链接");
    validateUrl(materialUrl);
    String copyText = clean(text(payload.get("copyText")), 20_000, "文案");
    String revenueSourceIds = normalizeSourceIds(payload.get("revenueSourceIds"));
    int campaignQuantity = positiveInteger(
        payload.get("campaignQuantity"), DEFAULT_CAMPAIGN_QUANTITY, 100, "推广系列数量");
    BigDecimal dailyBudget = positiveDecimal(
        payload.get("dailyBudget"), DEFAULT_DAILY_BUDGET, new BigDecimal("1000000"), "日预算");
    return new AccountVaultRepository.Entry(
        0, "ad_account", keyword, accountIds, "", "", articleUrl, materialUrl, copyText,
        revenueSourceIds, keyword,
        channel, styleId, country, campaignQuantity, dailyBudget,
        "", "", createdBy, updatedBy, null, null);
  }

  private static Map<String, Object> view(AccountVaultRepository.Entry entry) {
    return ReportService.mapOf(
        "id", entry.id(),
        "keyword", entry.keyword().isBlank() ? entry.name() : entry.keyword(),
        "accountIds", entry.accountId(),
        "accountCount", accountLines(entry.accountId()).size(),
        "country", entry.country(),
        "channelId", entry.channelId(),
        "styleId", entry.styleId(),
        "articleUrl", entry.url(),
        "materialUrl", entry.materialUrl(),
        "copyText", entry.copyText(),
        "revenueSourceIds", entry.revenueSourceIds(),
        "campaignQuantity", entry.campaignQuantity(),
        "dailyBudget", decimalText(entry.dailyBudget()),
        "createdBy", entry.createdBy(),
        "updatedAt", entry.updatedAt() == null ? "" : entry.updatedAt().toString());
  }

  private void ensureOptions(AccountVaultRepository.Entry entry) {
    repository.createOption("channel", entry.channelId(), entry.createdBy());
    repository.createOption("style_id", entry.styleId(), entry.createdBy());
  }

  private static void ensureUnused(
      AccountVaultRepository.Entry entry,
      long excludedId,
      List<AccountVaultRepository.Entry> existingEntries) {
    List<String> conflicts = new ArrayList<>();
    List<String> accounts = accountLines(entry.accountId());
    for (AccountVaultRepository.Entry existing : existingEntries) {
      if (excludedId > 0 && existing.id() == excludedId) continue;
      String keyword = existing.keyword().isBlank() ? existing.name() : existing.keyword();
      if (existing.channelId().equalsIgnoreCase(entry.channelId())) {
        conflicts.add("channel “" + entry.channelId() + "”已用于关键词“" + keyword + "”");
      }
      List<String> usedAccounts = accountLines(existing.accountId());
      for (String account : accounts) {
        if (usedAccounts.contains(account)) {
          conflicts.add("账户 ID “" + account + "”已用于关键词“" + keyword + "”");
        }
      }
    }
    if (!conflicts.isEmpty()) {
      throw new IllegalArgumentException("不能保存：" + String.join("；", conflicts.stream().distinct().toList()));
    }
  }

  private static Map<String, Object> optionView(AccountVaultRepository.OptionEntry option) {
    return ReportService.mapOf(
        "id", option.id(), "type", option.type(), "value", option.value(),
        "createdBy", option.createdBy());
  }

  private static Map<String, Object> channelOptionView(
      AccountVaultRepository.OptionEntry option, Map<String, List<String>> usage) {
    List<String> keywords = usage.getOrDefault(option.value().toLowerCase(), List.of()).stream()
        .distinct().toList();
    return ReportService.mapOf(
        "id", option.id(), "type", option.type(), "value", option.value(),
        "used", !keywords.isEmpty(), "usedBy", keywords);
  }

  private static String optionType(String value) {
    return switch (String.valueOf(value).trim()) {
      case "channel" -> "channel";
      case "style_id" -> "style_id";
      default -> throw new IllegalArgumentException("选项类型无效");
    };
  }

  private static String optionLabel(String type) {
    return "channel".equals(type) ? "channel" : "style ID";
  }

  private static String normalizeCountries(Object value) {
    String raw;
    if (value instanceof Iterable<?> values) {
      List<String> parts = new ArrayList<>();
      for (Object item : values) parts.add(text(item));
      raw = String.join(",", parts);
    } else {
      raw = text(value);
    }
    List<String> countries = new ArrayList<>();
    for (String part : raw.split("[\\r\\n,，、;；]+")) {
      String country = part.trim();
      if (!country.isBlank() && !countries.contains(country)) countries.add(country);
    }
    return clean(String.join(",", countries), 2000, "投放国家");
  }

  static List<Map<String, Object>> parseWorkbook(Workbook workbook) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Sheet sheet : workbook) {
      if (sheet.getPhysicalNumberOfRows() == 0) continue;
      Map<String, Integer> headers = headers(sheet.getRow(sheet.getFirstRowNum()));
      if (!headers.containsKey("关键词")
          || !(headers.containsKey("账户") || headers.containsKey("账户ID"))) continue;
      for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) continue;
        String keyword = value(row, headers, "关键词");
        String accounts = firstValue(row, headers, "账户ID", "账户");
        if (keyword.isBlank() && accounts.isBlank()) continue;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("keyword", keyword);
        item.put("accountIds", accounts);
        item.put("country", firstValue(row, headers, "投放国家", "国家"));
        item.put("channelId", value(row, headers, "channel"));
        item.put("styleId", firstValue(row, headers, "style ID", "styleid", "styleId"));
        item.put("articleUrl", firstValue(row, headers, "文章链接", "url", "URL"));
        item.put("materialUrl", firstValue(row, headers, "素材链接", "素材", "materialUrl"));
        item.put("copyText", firstValue(row, headers, "文案", "广告文案", "copyText"));
        item.put("revenueSourceIds", firstValue(row, headers, "收益活动ID", "收益活动", "sourceId"));
        item.put("campaignQuantity", firstValue(row, headers, "推广系列数量", "系列数量"));
        item.put("dailyBudget", firstValue(row, headers, "日预算", "预算"));
        rows.add(item);
      }
    }
    return rows;
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

  private static String firstValue(Row row, Map<String, Integer> headers, String... names) {
    for (String name : names) {
      String value = value(row, headers, name);
      if (!value.isBlank()) return value;
    }
    return "";
  }

  private static String value(Row row, Map<String, Integer> headers, String name) {
    Integer index = headers.get(name);
    return index == null ? "" : cell(row.getCell(index));
  }

  private static String cell(Cell cell) {
    return cell == null ? "" : new DataFormatter().formatCellValue(cell).trim();
  }

  private static String normalizeAccounts(String value) {
    List<String> accounts = accountLines(value);
    if (accounts.size() > 500) throw new IllegalArgumentException("单条映射最多保存 500 个账户");
    return clean(String.join("\n", accounts), 20_000, "账户 ID");
  }

  private static String normalizeSourceIds(Object value) {
    String raw;
    if (value instanceof Iterable<?> values) {
      List<String> parts = new ArrayList<>();
      for (Object item : values) parts.add(text(item));
      raw = String.join("\n", parts);
    } else {
      raw = text(value);
    }
    List<String> ids = raw.lines()
        .flatMap(line -> java.util.Arrays.stream(line.split("[,，;；]+")))
        .map(String::trim)
        .filter(id -> !id.isBlank())
        .distinct()
        .toList();
    if (ids.size() > 100) throw new IllegalArgumentException("单个关键词最多绑定 100 个收益活动");
    for (String id : ids) {
      if (!id.matches("^[A-Za-z0-9_-]{4,100}$")) {
        throw new IllegalArgumentException("收益活动 ID 格式无效");
      }
    }
    return String.join("\n", ids);
  }

  private static List<String> accountLines(String value) {
    return String.valueOf(value == null ? "" : value).lines()
        .map(String::trim)
        .filter(account -> !account.isBlank())
        .distinct()
        .toList();
  }

  private static String clean(String value, int max, String label) {
    String result = String.valueOf(value == null ? "" : value).trim();
    if (result.length() > max || result.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(label + "长度或格式无效");
    }
    return result;
  }

  private static int positiveInteger(Object value, int defaultValue, int max, String label) {
    String raw = text(value).trim();
    if (raw.isBlank()) return defaultValue;
    try {
      int result = Integer.parseInt(raw);
      if (result < 1 || result > max) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException ignored) {
      throw new IllegalArgumentException(label + "必须是 1 至 " + max + " 的整数");
    }
  }

  private static BigDecimal positiveDecimal(
      Object value, BigDecimal defaultValue, BigDecimal max, String label) {
    String raw = text(value).trim();
    if (raw.isBlank()) return defaultValue;
    try {
      BigDecimal result = new BigDecimal(raw);
      if (result.compareTo(new BigDecimal("0.01")) < 0 || result.compareTo(max) > 0) {
        throw new NumberFormatException();
      }
      if (result.stripTrailingZeros().scale() > 2) {
        throw new IllegalArgumentException(label + "最多保留两位小数");
      }
      return result.setScale(2, RoundingMode.UNNECESSARY);
    } catch (IllegalArgumentException error) {
      if (error instanceof NumberFormatException) {
        throw new IllegalArgumentException(label + "必须是 0.01 至 " + max.toPlainString() + " 的数字");
      }
      throw error;
    }
  }

  private static String decimalText(BigDecimal value) {
    BigDecimal normalized = value == null ? DEFAULT_DAILY_BUDGET : value;
    return normalized.stripTrailingZeros().toPlainString();
  }

  static void validateUrl(String value) {
    if (value.isBlank()) return;
    try {
      URI uri = URI.create(value.replace("{", "%7B").replace("}", "%7D"));
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
        throw new IllegalArgumentException();
      }
    } catch (Exception ignored) {
      throw new IllegalArgumentException("文章链接必须是有效的 http 或 https 地址");
    }
  }

  private static String text(Object value) {
    return String.valueOf(value == null ? "" : value);
  }
}
