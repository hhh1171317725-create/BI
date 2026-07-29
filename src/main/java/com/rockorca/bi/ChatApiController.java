package com.rockorca.bi;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {
  private final ChatService chat;

  public ChatApiController(ChatService chat) {
    this.chat = chat;
  }

  @GetMapping("/messages")
  public Map<String, Object> messages(@RequestParam(defaultValue = "公共聊天室") String room) {
    List<ChatService.ChatMessage> messages = chat.list(room);
    return ReportService.mapOf("room", room, "messages", messages);
  }

  @PostMapping("/messages")
  public ChatService.ChatMessage send(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> payload) {
    return chat.send(
        ReportService.text(payload.get("room")),
        ReportService.text(payload.get("sender")),
        ReportService.text(payload.get("text")));
  }

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ChatService.ChatMessage upload(
      @RequestParam(defaultValue = "公共聊天室") String room,
      @RequestParam(defaultValue = "") String sender,
      @RequestParam(defaultValue = "") String text,
      @RequestPart("file") MultipartFile file) {
    return chat.upload(room, sender, text, file);
  }

  @GetMapping("/files/{id}")
  public ResponseEntity<FileSystemResource> file(@PathVariable String id) throws Exception {
    ChatService.ChatAttachment attachment = chat.attachment(id);
    var path = chat.filePath(id);
    if (!Files.isRegularFile(path)) throw new IllegalArgumentException("文件不存在或已失效");
    MediaType contentType;
    try {
      contentType = MediaType.parseMediaType(attachment.contentType());
    } catch (IllegalArgumentException ignored) {
      contentType = MediaType.APPLICATION_OCTET_STREAM;
    }
    boolean image = contentType.getType().equalsIgnoreCase("image");
    ContentDisposition disposition = (image ? ContentDisposition.inline() : ContentDisposition.attachment())
        .filename(attachment.name(), java.nio.charset.StandardCharsets.UTF_8)
        .build();
    return ResponseEntity.ok().contentType(contentType).contentLength(Files.size(path))
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .body(new FileSystemResource(path));
  }
}
