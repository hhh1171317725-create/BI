package com.rockorca.bi;

import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FromStringTerm;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class MailDingtalkService {
  private static final String TIKTOK_NOTIFICATION_SENDER = "no-reply@notifications.tiktok.com";
  private static final int MAX_MESSAGES_PER_RUN = 20;
  private static final int MAX_HISTORY = 2_000;
  private static final int MAX_SOURCE_CHARS = 50_000;
  private static final int MAX_MESSAGE_BODY_CHARS = 3_200;
  private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("(?i)Ad account ID\\s*:\\s*(\\d+)");
  private static final Pattern ACCOUNT_NAME_PATTERN = Pattern.compile(
      "(?is)Ad account name\\s*:\\s*(.+?)(?=\\s+(?:View rejection details|Not delivering reason|Further Details|How to fix|Policy Violation|Affected countries|Ad Group ID|Campaign ID)\\b)");
  private static final Pattern AD_GROUP_IDS_PATTERN = Pattern.compile(
      "(?i)(?:Ad\\s*Group|Campaign)\\s*ID(?:s|\\(s\\))?\\s*[:：#-]?\\s*([0-9][0-9,，;；\\s]*)");
  private static final Pattern REVIEW_REASON_PATTERN = Pattern.compile(
      "(?is)Our review indicates that\\s+(.+?)(?=\\s+We proactively enforce|\\s+Ad Group ID\\s*:|$)");
  private static final Pattern DETAILS_REASON_PATTERN = Pattern.compile(
      "(?is)See the following for details\\s*[:：]?\\s*(.+?)(?=\\s+(?:Ad Group|Campaign)\\s*ID|\\s+Training videos|$)");
  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private volatile boolean running;
  private volatile String lastRunAt = "";
  private volatile String lastResult = "尚未执行";

  public MailDingtalkService(RuntimeConfig config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
  }

  public synchronized Map<String, Object> status() {
    Credentials credentials = credentials();
    return ReportService.mapOf(
        "configured", credentials.configured(),
        "email", credentials.email(),
        "authorizationCodeSaved", !credentials.authorizationCode().isBlank(),
        "webhookSaved", !credentials.webhook().isBlank(),
        "secretSaved", !credentials.secret().isBlank(),
        "keyword", credentials.keyword(),
        "autoEnabled", credentials.autoEnabled(),
        "intervalMinutes", 5,
        "running", running,
        "lastRunAt", lastRunAt,
        "lastResult", lastResult);
  }

  public synchronized Map<String, Object> saveSettings(
      String email, String authorizationCode, String webhook, String secret, String keyword, boolean autoEnabled) {
    config.saveMailDingtalkCredentials(email, authorizationCode, webhook, secret, keyword, autoEnabled);
    return status();
  }

  public synchronized Map<String, Object> forwardNow() {
    if (running) throw new IllegalStateException("邮件转发正在执行，请稍后再试");
    running = true;
    try {
      ForwardResult result = forward(credentials());
      lastRunAt = ZonedDateTime.now(ReportService.BEIJING).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      lastResult = "本次已转发 " + result.sent() + " 封邮件";
      return ReportService.mapOf("sent", result.sent(), "skipped", result.skipped(), "message", lastResult,
          "items", result.items());
    } catch (RuntimeException error) {
      lastRunAt = ZonedDateTime.now(ReportService.BEIJING).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      lastResult = "失败：" + error.getMessage();
      throw error;
    } finally {
      running = false;
    }
  }

  public synchronized Map<String, Object> testLatest() {
    if (running) throw new IllegalStateException("邮件转发正在执行，请稍后再试");
    running = true;
    try {
      Credentials credentials = credentials();
      credentials.validate();
      MailSummary summary = latestTikTokMail(credentials);
      sendTextToDingtalk("【测试发送，不影响正式转发】\n" + formatDingtalkMessage(summary), credentials);
      lastRunAt = ZonedDateTime.now(ReportService.BEIJING).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      lastResult = "最新一封 TikTok 邮件测试发送成功";
      return ReportService.mapOf("message", lastResult, "subject", summary.subject());
    } catch (Exception error) {
      RuntimeException failure = error instanceof RuntimeException runtime
          ? runtime : new IllegalStateException("测试发送失败：" + rootMessage(error), error);
      lastRunAt = ZonedDateTime.now(ReportService.BEIJING).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      lastResult = "测试失败：" + failure.getMessage();
      throw failure;
    } finally {
      running = false;
    }
  }

  @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)
  public void scheduledForward() {
    Credentials credentials = credentials();
    if (!credentials.autoEnabled() || !credentials.configured()) return;
    try {
      forwardNow();
    } catch (Exception error) {
      System.err.println("QQ 邮箱钉钉转发失败：" + error.getMessage());
    }
  }

  private ForwardResult forward(Credentials credentials) {
    credentials.validate();
    Set<String> history = readHistory();
    List<String> newlyForwarded = new ArrayList<>();
    List<Map<String, Object>> items = new ArrayList<>();
    int skipped = 0;
    Properties properties = new Properties();
    properties.put("mail.store.protocol", "imaps");
    properties.put("mail.imaps.host", "imap.qq.com");
    properties.put("mail.imaps.port", "993");
    properties.put("mail.imaps.ssl.enable", "true");
    properties.put("mail.imaps.connectiontimeout", "15000");
    properties.put("mail.imaps.timeout", "30000");
    try (Store store = Session.getInstance(properties).getStore("imaps")) {
      store.connect("imap.qq.com", credentials.email(), credentials.authorizationCode());
      Folder inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_WRITE);
      try {
        // Do not rely on the unread flag: QQ clients may mark a new message as read
        // before this scheduled job sees it. Message-ID history remains the source
        // of truth for exactly-once delivery.
        Message[] matching = inbox.search(new FromStringTerm(TIKTOK_NOTIFICATION_SENDER));
        List<Message> messages = new ArrayList<>(List.of(matching));
        messages.sort(Comparator.comparing(MailDingtalkService::sentDateSafe).reversed());
        for (Message message : messages) {
          if (items.size() >= MAX_MESSAGES_PER_RUN) break;
          if (!fromTargetSender(message)) {
            skipped++;
            continue;
          }
          String key = messageKey(message);
          if (history.contains(key)) {
            skipped++;
            continue;
          }
          MailSummary summary = summarize(message);
          sendToDingtalk(summary, credentials);
          message.setFlag(Flags.Flag.SEEN, true);
          history.add(key);
          newlyForwarded.add(key);
          items.add(ReportService.mapOf("subject", summary.subject(), "from", summary.from()));
        }
      } finally {
        inbox.close(true);
      }
    } catch (Exception error) {
      throw new IllegalStateException("邮件转发失败：" + rootMessage(error), error);
    }
    if (!newlyForwarded.isEmpty()) writeHistory(history);
    return new ForwardResult(newlyForwarded.size(), skipped, items);
  }

  private MailSummary latestTikTokMail(Credentials credentials) {
    Properties properties = mailProperties();
    try (Store store = Session.getInstance(properties).getStore("imaps")) {
      store.connect("imap.qq.com", credentials.email(), credentials.authorizationCode());
      Folder inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_ONLY);
      try {
        List<Message> messages = new ArrayList<>(List.of(
            inbox.search(new FromStringTerm(TIKTOK_NOTIFICATION_SENDER))));
        messages.removeIf(message -> !fromTargetSender(message));
        messages.sort(Comparator.comparing(MailDingtalkService::sentDateSafe).reversed());
        if (messages.isEmpty()) {
          throw new IllegalStateException("收件箱中没有找到 TikTok 通知邮件");
        }
        return summarize(messages.get(0));
      } finally {
        inbox.close(false);
      }
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("读取最新 TikTok 邮件失败：" + rootMessage(error), error);
    }
  }

  private static Properties mailProperties() {
    Properties properties = new Properties();
    properties.put("mail.store.protocol", "imaps");
    properties.put("mail.imaps.host", "imap.qq.com");
    properties.put("mail.imaps.port", "993");
    properties.put("mail.imaps.ssl.enable", "true");
    properties.put("mail.imaps.connectiontimeout", "15000");
    properties.put("mail.imaps.timeout", "30000");
    return properties;
  }

  private MailSummary summarize(Message message) throws Exception {
    String subject = emptyToDefault(message.getSubject(), "（无主题）");
    String from = displayFrom(message);
    String sentAt = message.getSentDate() == null ? "" : message.getSentDate().toInstant()
        .atZone(ReportService.BEIJING).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    List<String> attachments = new ArrayList<>();
    String content = readContent(message, attachments);
    content = content.replaceAll("[\\t\\x0B\\f\\r ]+", " ")
        .replaceAll("\\n\\s*\\n+", "\n").trim();
    if (content.length() > MAX_SOURCE_CHARS) content = content.substring(0, MAX_SOURCE_CHARS) + "...";
    return new MailSummary(subject, from, sentAt, content, attachments);
  }

  private String readContent(Part part, List<String> attachments) throws Exception {
    Object content = part.getContent();
    if (content == null) return "";
    if (content instanceof String text) return part.isMimeType("text/html") ? htmlToText(text) : text;
    if (!(content instanceof Multipart multipart)) return String.valueOf(content);
    String richestContent = "";
    for (int index = 0; index < multipart.getCount(); index++) {
      BodyPart bodyPart = multipart.getBodyPart(index);
      String disposition = bodyPart.getDisposition();
      if (bodyPart.getFileName() != null || Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
        attachments.add(bodyPart.getFileName() == null ? "附件" : bodyPart.getFileName());
      } else {
        String value = readContent(bodyPart, attachments);
        if (value.isBlank()) continue;
        // TikTok multipart/alternative messages often contain a short plain-text
        // body and a complete HTML body. Keep the richest version so IDs and the
        // policy reason are not discarded with the HTML part.
        if (informationScore(value) > informationScore(richestContent)) richestContent = value;
      }
    }
    return richestContent;
  }

  static int informationScore(String value) {
    if (value == null || value.isBlank()) return 0;
    String lower = normalizeForParsing(value).toLowerCase();
    int score = Math.min(value.length(), MAX_SOURCE_CHARS);
    if (lower.contains("ad group id") || lower.contains("campaign id")) score += 100_000;
    if (lower.contains("our review indicates") || lower.contains("policy violation")) score += 50_000;
    if (lower.contains("ad account id")) score += 20_000;
    return score;
  }

  private static String displayFrom(Message message) {
    try {
      if (message.getFrom() == null || message.getFrom().length == 0) return "";
      jakarta.mail.Address sender = message.getFrom()[0];
      if (!(sender instanceof InternetAddress address)) return sender.toString();
      String personal = address.getPersonal();
      String email = emptyToDefault(address.getAddress(), sender.toString());
      return personal == null || personal.isBlank() ? email : personal + " <" + email + ">";
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String htmlToText(String html) {
    String text = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
        .replaceAll("(?i)<br\\s*/?>", "\n")
        .replaceAll("(?i)</(p|div|li|tr|h[1-6])\\s*>", "\n")
        .replaceAll("(?s)<[^>]+>", " ");
    text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", "> ").replace("&quot;", "\"").replace("&#39;", "'");
    Matcher matcher = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(text);
    StringBuffer decoded = new StringBuffer();
    while (matcher.find()) {
      try {
        String value = matcher.group(1);
        int codePoint = value.startsWith("x") ? Integer.parseInt(value.substring(1), 16) : Integer.parseInt(value);
        matcher.appendReplacement(decoded, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
      } catch (Exception ignored) {
        matcher.appendReplacement(decoded, Matcher.quoteReplacement(matcher.group()));
      }
    }
    matcher.appendTail(decoded);
    return decoded.toString();
  }

  private void sendToDingtalk(MailSummary mail, Credentials credentials) throws Exception {
    sendTextToDingtalk(formatDingtalkMessage(mail), credentials);
  }

  private void sendTextToDingtalk(String text, Credentials credentials) throws Exception {
    String payloadText = credentials.keyword().isBlank() ? text : credentials.keyword() + "\n" + text;
    String body = objectMapper.writeValueAsString(Map.of(
        "msgtype", "text", "text", Map.of("content", payloadText)));
    HttpRequest request = HttpRequest.newBuilder(URI.create(signedWebhook(credentials)))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json;charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException("钉钉机器人返回 HTTP " + response.statusCode());
    }
    Map<String, Object> root = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
    if ((int) ReportService.number(root.get("errcode")) != 0) {
      throw new IllegalStateException("钉钉机器人发送失败：" + ReportService.text(root.get("errmsg")));
    }
  }

  private static String formatDingtalkMessage(MailSummary mail) {
    TikTokBudgetNotice budget = parseTikTokBudgetNotice(mail.subject(), mail.content());
    if (budget.isBudgetNotice()) {
      return new StringBuilder("【TikTok 预算通知】\n")
          .append("账户 ID：").append(emptyToDefault(budget.accountId(), "未识别")).append("\n")
          .append("账户名称：").append(emptyToDefault(budget.accountName(), "未识别")).append("\n")
          .append("系列 ID：").append(budget.campaignIds().isEmpty()
              ? "未识别" : String.join("、", budget.campaignIds())).append("\n")
          .append("预算状态：").append(budget.status()).append("\n")
          .append("邮件主题：").append(mail.subject())
          .toString();
    }
    TikTokViolation violation = parseTikTokViolation(mail.subject() + "\n" + mail.content());
    if (violation.hasDetails()) {
      return new StringBuilder("【TikTok 广告拒审通知】\n")
          .append("账户 ID：").append(emptyToDefault(violation.accountId(), "未识别")).append("\n")
          .append("账户名称：").append(emptyToDefault(violation.accountName(), "未识别")).append("\n")
          .append("系列 ID：").append(violation.adGroupIds().isEmpty()
              ? "未识别" : String.join("、", violation.adGroupIds())).append("\n")
          .append("拒审原因：").append(emptyToDefault(violation.reason(), "邮件未提供明确原因"))
          .toString();
    }
    String fallbackContent = mail.content().isBlank() ? "（邮件正文为空）" : mail.content();
    if (fallbackContent.length() > MAX_MESSAGE_BODY_CHARS) {
      fallbackContent = fallbackContent.substring(0, MAX_MESSAGE_BODY_CHARS) + "...";
    }
    StringBuilder text = new StringBuilder("【TikTok 邮件通知】\n")
        .append("主题：").append(mail.subject()).append("\n")
        .append("时间：").append(mail.sentAt()).append("\n\n")
        .append(fallbackContent);
    if (!mail.attachments().isEmpty()) text.append("\n\n附件：").append(String.join("、", mail.attachments()));
    return text.toString();
  }

  private static TikTokViolation parseTikTokViolation(String content) {
    content = normalizeForParsing(content);
    String accountId = firstMatch(ACCOUNT_ID_PATTERN, content);
    String accountName = cleanField(firstMatch(ACCOUNT_NAME_PATTERN, content));
    List<String> adGroupIds = extractAdGroupIds(content);
    String reason = extractViolationReason(content);
    return new TikTokViolation(accountId, accountName, adGroupIds, reason);
  }

  static List<String> extractAdGroupIds(String content) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    Matcher groups = AD_GROUP_IDS_PATTERN.matcher(normalizeForParsing(content));
    while (groups.find()) {
      for (String value : groups.group(1).split("\\D+")) {
        if (value.length() >= 6) ids.add(value);
      }
    }
    return List.copyOf(ids);
  }

  static String extractViolationReason(String content) {
    String normalized = normalizeForParsing(content);
    String lower = normalized.toLowerCase();
    if (lower.contains("sexually suggestive") || lower.contains("sexualised content")
        || lower.contains("sexualized content")) {
      return "素材可能包含或推广性暗示内容，包括性暗示文字、音频、动作、性行为暗示或敏感部位暗示，违反 TikTok 广告政策。";
    }
    if (lower.contains("misleading") || lower.contains("deceptive")) {
      return "素材或落地页可能包含误导、欺骗或夸大信息，违反 TikTok 广告政策。";
    }
    if (lower.contains("before-and-after") || lower.contains("before and after")) {
      return "素材可能包含效果前后对比或不当效果承诺，违反 TikTok 广告政策。";
    }
    if (lower.contains("personal attributes")) {
      return "素材可能直接或间接指向用户的个人属性，违反 TikTok 广告政策。";
    }
    String reason = cleanField(firstMatch(REVIEW_REASON_PATTERN, normalized));
    if (reason.isBlank()) reason = cleanField(firstMatch(DETAILS_REASON_PATTERN, normalized));
    return reason;
  }

  static TikTokBudgetNotice parseTikTokBudgetNotice(String subject, String content) {
    String source = normalizeForParsing(emptyToDefault(subject, "") + "\n" + emptyToDefault(content, ""));
    String lower = source.toLowerCase();
    boolean budgetNotice = lower.contains("budget") || lower.contains("account balance")
        || lower.contains("insufficient balance") || lower.contains("余额") || lower.contains("预算");
    if (!budgetNotice) return new TikTokBudgetNotice(false, "", "", List.of(), "");

    String status;
    if (containsAny(lower, "budget has been exhausted", "budget is exhausted", "out of budget",
        "reached its budget", "budget limit has been reached", "budget cap")) {
      status = "预算已用尽或达到上限，相关广告可能已停止或减少投放。";
    } else if (containsAny(lower, "running low", "low balance", "insufficient balance",
        "insufficient budget", "balance is low")) {
      status = "预算或账户余额不足，可能影响广告继续投放，请及时检查并补充。";
    } else if (containsAny(lower, "budget changed", "budget has changed", "budget updated",
        "budget adjusted")) {
      status = "预算设置发生变化，请进入 TikTok Ads Manager 核对最新预算。";
    } else if (containsAny(lower, "daily budget", "lifetime budget")) {
      status = "广告预算需要关注，请检查日预算、总预算及当前消耗。";
    } else {
      status = "检测到预算或余额相关通知，请进入 TikTok Ads Manager 查看详情。";
    }
    return new TikTokBudgetNotice(true,
        firstMatch(ACCOUNT_ID_PATTERN, source),
        cleanField(firstMatch(ACCOUNT_NAME_PATTERN, source)),
        extractAdGroupIds(source), status);
  }

  private static boolean containsAny(String source, String... values) {
    for (String value : values) if (source.contains(value)) return true;
    return false;
  }

  private static String normalizeForParsing(String content) {
    return (content == null ? "" : content)
        .replace('\u00a0', ' ')
        .replace('\u202f', ' ')
        .replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
  }

  private static String firstMatch(Pattern pattern, String content) {
    Matcher matcher = pattern.matcher(content == null ? "" : content);
    return matcher.find() ? matcher.group(1).trim() : "";
  }

  private static String cleanField(String value) {
    return value == null ? "" : value.replaceAll("\\s+", " ").trim();
  }

  private String signedWebhook(Credentials credentials) {
    if (credentials.secret().isBlank()) return credentials.webhook();
    try {
      String timestamp = String.valueOf(System.currentTimeMillis());
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(credentials.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String sign = Base64.getEncoder().encodeToString(mac.doFinal((timestamp + "\n" + credentials.secret())
          .getBytes(StandardCharsets.UTF_8)));
      return credentials.webhook() + "&timestamp=" + timestamp + "&sign="
          + URLEncoder.encode(sign, StandardCharsets.UTF_8);
    } catch (NoSuchAlgorithmException | InvalidKeyException error) {
      throw new IllegalStateException("钉钉机器人加签失败", error);
    }
  }

  private Set<String> readHistory() {
    Path path = config.runtimeDir().resolve("mail-dingtalk-forwarded.json");
    if (!Files.isRegularFile(path)) return new LinkedHashSet<>();
    try {
      return new LinkedHashSet<>(objectMapper.readValue(Files.readString(path), new TypeReference<List<String>>() {}));
    } catch (Exception ignored) {
      return new LinkedHashSet<>();
    }
  }

  private void writeHistory(Set<String> keys) {
    try {
      List<String> recent = new ArrayList<>(keys);
      if (recent.size() > MAX_HISTORY) recent = recent.subList(recent.size() - MAX_HISTORY, recent.size());
      Files.createDirectories(config.runtimeDir());
      Files.writeString(config.runtimeDir().resolve("mail-dingtalk-forwarded.json"), objectMapper.writeValueAsString(recent));
    } catch (Exception error) {
      throw new IllegalStateException("保存邮件转发记录失败：" + rootMessage(error), error);
    }
  }

  private Credentials credentials() {
    return new Credentials(config.get("MAIL_DINGTALK_QQ_EMAIL", ""),
        config.decodedSecret("MAIL_DINGTALK_QQ_AUTH_CODE_B64"),
        config.decodedSecret("MAIL_DINGTALK_WEBHOOK_B64"),
        config.decodedSecret("MAIL_DINGTALK_SECRET_B64"),
        config.get("MAIL_DINGTALK_KEYWORD", ""),
        "true".equalsIgnoreCase(config.get("MAIL_DINGTALK_AUTO_ENABLED", "true")));
  }

  private static java.util.Date sentDateSafe(Message message) {
    try { return message.getSentDate() == null ? new java.util.Date(0) : message.getSentDate(); }
    catch (Exception ignored) { return new java.util.Date(0); }
  }

  private static String messageKey(Message message) throws Exception {
    String[] ids = message.getHeader("Message-ID");
    return ids != null && ids.length > 0 && !ids[0].isBlank() ? ids[0] : message.getFolder().getFullName() + ":" + message.getMessageNumber();
  }

  private static boolean fromTargetSender(Message message) {
    try {
      if (message.getFrom() == null) return false;
      for (jakarta.mail.Address sender : message.getFrom()) {
        if (sender instanceof InternetAddress address
            && TIKTOK_NOTIFICATION_SENDER.equalsIgnoreCase(address.getAddress())) {
          return true;
        }
      }
      return false;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static String emptyToDefault(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value.trim();
  }

  private static String rootMessage(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null) root = root.getCause();
    return emptyToDefault(root.getMessage(), root.getClass().getSimpleName());
  }

  private record Credentials(
      String email, String authorizationCode, String webhook, String secret, String keyword, boolean autoEnabled) {
    boolean configured() { return email.endsWith("@qq.com") && !authorizationCode.isBlank() && webhook.startsWith("https://oapi.dingtalk.com/"); }
    void validate() { if (!configured()) throw new IllegalStateException("请先配置 QQ 邮箱授权码和钉钉机器人 Webhook"); }
  }
  private record MailSummary(String subject, String from, String sentAt, String content, List<String> attachments) {}
  private record TikTokViolation(String accountId, String accountName, List<String> adGroupIds, String reason) {
    boolean hasDetails() { return !accountId.isBlank() || !accountName.isBlank() || !adGroupIds.isEmpty(); }
  }
  record TikTokBudgetNotice(
      boolean isBudgetNotice, String accountId, String accountName, List<String> campaignIds, String status) {}
  private record ForwardResult(int sent, int skipped, List<Map<String, Object>> items) {}
}
