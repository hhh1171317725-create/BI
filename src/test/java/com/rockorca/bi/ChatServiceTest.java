package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ChatServiceTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void clearsOnlySelectedRoomAndDeletesItsAttachments() {
    ObjectMapper objectMapper = new ObjectMapper();
    RuntimeConfig config = new RuntimeConfig(objectMapper);
    ReflectionTestUtils.setField(config, "runtimeDir", temporaryDirectory);
    ChatService chat = new ChatService(config, objectMapper);
    chat.load();
    ChatService.ChatMessage uploaded = chat.upload(
        "公共聊天室", "电脑 A", "图片", new MockMultipartFile(
            "file", "photo.png", "image/png", new byte[] {1, 2, 3}));
    chat.send("其他房间", "电脑 B", "保留我");
    Path attachment = chat.filePath(uploaded.attachment().id());
    assertTrue(Files.isRegularFile(attachment));

    ChatService.ClearResult result = chat.clearRoom("公共聊天室");

    assertEquals(1, result.messagesDeleted());
    assertEquals(1, result.filesDeleted());
    assertEquals(0, result.fileDeleteFailures());
    assertTrue(chat.list("公共聊天室").isEmpty());
    assertEquals(1, chat.list("其他房间").size());
    assertFalse(Files.exists(attachment));
  }
}
