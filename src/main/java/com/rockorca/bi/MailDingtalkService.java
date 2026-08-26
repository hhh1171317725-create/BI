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
import jakarta.mail.search.FlagTerm;
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
  private static final int MAX_BODY_CHARS = 3_200;
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
        "autoEnabled", credentials.autoEnabled(),
        "intervalMinutes", 5,
        "running", running,
        "lastRunAt", lastRunAt,
        "lastResult", lastResult);
  }

  public synchronized Map<String, Object> saveSettings(
      String email, String authorizationCode, String webhook, String secret, boolean autoEnabled) {
    config.saveMailDingtalkCredentials(email, authorizationCode, webhook, secret, autoEnabled);
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
        Message[] unread = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        List<Message> messages = new ArrayList<>(List.of(unread));
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

  private MailSummary summarize(Message message) throws Exception {
    String subject = emptyToDefault(message.getSubject(), "（无主题）");
    String from = "";
    if (message.getFrom() != null && message.getFrom().length > 0) {
      from = InternetAddress.toString(message.getFrom());
    }
    String sentAt = message.getSentDate() == null ? "" : message.getSentDate().toInstant()
        .atZone(ReportService.BEIJING).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    List<String> attachments = new ArrayList<>();
    String content = readContent(message.getContent(), attachments);
    content = content.replaceAll("\\s+", " ").trim();
    if (content.length() > MAX_BODY_CHARS) content = content.substring(0, MAX_BODY_CHARS) + "...";
    return new MailSummary(subject, from, sentAt, content, attachments);
  }

  private String readContent(Object content, List<String> attachments) throws Exception {
    if (content == null) return "";
    if (content instanceof String text) return text;
    if (!(content instanceof Multipart multipart)) return String.valueOf(content);
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < multipart.getCount(); index++) {
      BodyPart part = multipart.getBodyPart(index);
      String disposition = part.getDisposition();
      if (part.getFileName() != null || Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
        attachments.add(part.getFileName() == null ? "附件" : part.getFileName());
      } else {
        String value = readContent(part.getContent(), attachments);
        if (!value.isBlank() && result.isEmpty()) result.append(value);
      }
    }
    return result.toString();
  }

  private void sendToDingtalk(MailSummary mail, Credentials credentials) throws Exception {
    StringBuilder text = new StringBuilder("【QQ 邮箱新邮件】\n")
        .append("主题：").append(mail.subject()).append("\n")
        .append("发件人：").append(mail.from()).append("\n")
        .append("时间：").append(mail.sentAt()).append("\n\n")
        .append(mail.content().isBlank() ? "（邮件正文为空）" : mail.content());
    if (!mail.attachments().isEmpty()) {
      text.append("\n\n附件：").append(String.join("、", mail.attachments()));
    }
    String body = objectMapper.writeValueAsString(Map.of(
        "msgtype", "text", "text", Map.of("content", text.toString())));
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

  private record Credentials(String email, String authorizationCode, String webhook, String secret, boolean autoEnabled) {
    boolean configured() { return email.endsWith("@qq.com") && !authorizationCode.isBlank() && webhook.startsWith("https://oapi.dingtalk.com/"); }
    void validate() { if (!configured()) throw new IllegalStateException("请先配置 QQ 邮箱授权码和钉钉机器人 Webhook"); }
  }
  private record MailSummary(String subject, String from, String sentAt, String content, List<String> attachments) {}
  private record ForwardResult(int sent, int skipped, List<Map<String, Object>> items) {}
}
