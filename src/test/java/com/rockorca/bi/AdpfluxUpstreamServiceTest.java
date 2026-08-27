package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AdpfluxUpstreamServiceTest {
  @Test
  void explainsExpiredConsumptionToken() {
    assertEquals(
        "消耗 AuthorizationFront 已过期，请重新登录 ADPFlux，更新页面最上方的消耗凭据后保存并重试",
        AdpfluxUpstreamService.upstreamError("server_token_has_expired_log_in_again"));
  }
}
