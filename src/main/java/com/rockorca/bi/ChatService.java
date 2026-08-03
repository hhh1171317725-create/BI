package com.rockorca.bi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 供已登录设备共享文字和文件的轻量聊天室存储。 */
@Service
public class ChatService {
  private static final int MAX_MESSAGES = 1_000;
  private final RuntimeConfig config;
  private final ObjectMapper objectMapper;
  private final List<ChatMessage> messages = new ArrayList<>();
  private Path messagesPath;
  private Path uploadsDir;

  public ChatService(RuntimeConfig config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void load() {
    try {
      Path chatDir = config.runtimeDir().resolve("chat");
      uploadsDir = chatDir.resolve("uploads");
      messagesPath = chatDir.resolve("messages.json");
      Files.createDirectories(uploadsDir);
      if (Files.isRegularFile(messagesPath)) {
        messages.addAll(objectMapper.readValue(messagesPath.toFile(), new TypeReference<List<ChatMessage>>() {}));
      }
    } catch (IOException error) {
      throw new IllegalStateException("初始化聊天室存储失败：" + error.getMessage(), error);
    }
  }

  public synchronized List<ChatMessage> list(String roomValue) {
    String room = room(roomValue);
    return messages.stream().filter(message -> room.equals(message.room()))
        .sorted(Comparator.comparing(ChatMessage::createdAt))
        .skip(Math.max(0, messages.stream().filter(message -> room.equals(message.room())).count() - 200))
        .toList();
  }

  public synchronized ChatMessage send(String roomValue, String senderValue, String textValue) {
    String text = text(textValue);
    if (text.isBlank()) throw new IllegalArgumentException("请输入要发送的内容");
    return append(room(roomValue), sender(senderValue), text, null);
  }

  public synchronized ChatMessage upload(
      String roomValue, String senderValue, String textValue, MultipartFile file) {
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要上传的文件");
    try {
      String originalName = safeFileName(file.getOriginalFilename());
      String id = UUID.randomUUID().toString();
      Path target = uploadsDir.resolve(id);
      try (InputStream input = file.getInputStream()) {
        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
      }
      ChatAttachment attachment = new ChatAttachment(
          id, originalName, contentType(file.getContentType()), file.getSize());
      return append(room(roomValue), sender(senderValue), text(textValue), attachment);
    } catch (IOException error) {
      throw new IllegalStateException("保存上传文件失败：" + error.getMessage(), error);
    }
  }

  public synchronized ChatAttachment attachment(String id) {
    return messages.stream().map(ChatMessage::attachment).filter(value -> value != null)
        .filter(value -> value.id().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("文件不存在或已失效"));
  }

  public Path filePath(String id) {
    return uploadsDir.resolve(id).normalize();
  }

  public synchronized ClearResult clearRoom(String roomValue) {
    String targetRoom = room(roomValue);
    List<ChatMessage> removed = messages.stream()
        .filter(message -> targetRoom.equals(message.room()))
        .toList();
    if (removed.isEmpty()) return new ClearResult(targetRoom, 0, 0, 0);

    List<ChatMessage> original = new ArrayList<>(messages);
    messages.removeAll(removed);
    try {
      save();
    } catch (RuntimeException error) {
      messages.clear();
      messages.addAll(original);
      throw error;
    }

    int filesDeleted = 0;
    int fileDeleteFailures = 0;
    for (ChatMessage message : removed) {
      if (message.attachment() == null) continue;
      try {
        if (Files.deleteIfExists(filePath(message.attachment().id()))) filesDeleted++;
      } catch (IOException ignored) {
        fileDeleteFailures++;
      }
    }
    return new ClearResult(targetRoom, removed.size(), filesDeleted, fileDeleteFailures);
  }

  private ChatMessage append(String room, String sender, String text, ChatAttachment attachment) {
    ChatMessage message = new ChatMessage(UUID.randomUUID().toString(), room, sender, text, attachment, Instant.now().toString());
    messages.add(message);
    while (messages.size() > MAX_MESSAGES) messages.removeFirst();
    save();
    return message;
  }

  private void save() {
    try {
      Path temporary = messagesPath.resolveSibling("messages.json.tmp");
      objectMapper.writeValue(temporary.toFile(), messages);
      try {
        Files.move(temporary, messagesPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, messagesPath, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException error) {
      throw new IllegalStateException("保存聊天室消息失败：" + error.getMessage(), error);
    }
  }

  private static String room(String value) {
    String room = String.valueOf(value == null ? "" : value).trim();
    if (room.isBlank()) return "公共聊天室";
    if (room.length() > 32) throw new IllegalArgumentException("房间名称不能超过 32 个字符");
    return room;
  }

  private static String sender(String value) {
    String sender = String.valueOf(value == null ? "" : value).trim();
    if (sender.isBlank()) return "匿名设备";
    return sender.substring(0, Math.min(sender.length(), 32));
  }

  private static String text(String value) {
    String text = String.valueOf(value == null ? "" : value).trim();
    if (text.length() > 4_000) throw new IllegalArgumentException("单条消息不能超过 4000 个字符");
    return text;
  }

  private static String safeFileName(String value) {
    String name = Path.of(String.valueOf(value == null ? "" : value)).getFileName().toString().trim();
    if (name.isBlank()) name = "未命名文件";
    return name.substring(0, Math.min(name.length(), 180));
  }

  private static String contentType(String value) {
    return value == null || value.isBlank() ? "application/octet-stream" : value;
  }

  public record ChatAttachment(String id, String name, String contentType, long size) {}

  public record ChatMessage(
      String id,
      String room,
      String sender,
      String text,
      ChatAttachment attachment,
      String createdAt) {}

  public record ClearResult(String room, int messagesDeleted, int filesDeleted, int fileDeleteFailures) {}
}
