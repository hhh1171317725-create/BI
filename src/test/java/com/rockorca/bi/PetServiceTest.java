package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PetServiceTest {
  @Test
  void aiConfigStatusNeverReturnsTheApiKey() {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.get("AI_PROVIDER", "")).thenReturn("deepseek");
    when(config.get("DEEPSEEK_API_KEY", "")).thenReturn("sk-server-secret");
    when(config.get("DEEPSEEK_MODEL", "deepseek-v4-flash")).thenReturn("deepseek-chat");
    when(config.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
        .thenReturn("https://api.deepseek.com");
    PetService service = new PetService(null, null, config, new ObjectMapper());

    Map<String, Object> status = service.aiConfigStatus();

    assertEquals("deepseek", status.get("provider"));
    assertEquals("deepseek-chat", status.get("model"));
    assertEquals(true, status.get("configured"));
    assertFalse(status.containsKey("apiKey"));
  }

  @Test
  void savingAiConfigDelegatesToServerRuntimeConfig() {
    RuntimeConfig config = mock(RuntimeConfig.class);
    when(config.get("AI_PROVIDER", "")).thenReturn("openai");
    when(config.get("OPENAI_API_KEY", "")).thenReturn("sk-server-secret");
    when(config.get("OPENAI_MODEL", "gpt-5.6-terra")).thenReturn("gpt-5.6-terra");
    PetService service = new PetService(null, null, config, new ObjectMapper());

    service.saveAiConfig("openai", "sk-server-secret", "gpt-5.6-terra");

    verify(config).saveAiCredentials("openai", "sk-server-secret", "gpt-5.6-terra");
  }
}
