package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JdDeeplinkServiceTest {
  @Test
  void usesExternalUrlAsLandingPage() {
    JdDeeplinkService service = new JdDeeplinkService(
        new RuntimeConfig(new ObjectMapper()), new ObjectMapper());
    JdDeeplinkService.JdProduct product = new JdDeeplinkService.JdProduct(
        "10159027099728", "test", "https://pro.m.jd.com/product");

    Map<String, Object> request = service.requestBody(product);

    assertEquals("https://pro.m.jd.com/product", request.get("lp_url"));
    assertFalse(request.containsKey("h5"));
  }

  @Test
  void packagedProductsContainExternalUrls() {
    JdDeeplinkService service = new JdDeeplinkService(
        new RuntimeConfig(new ObjectMapper()), new ObjectMapper());
    service.loadProducts();

    JdDeeplinkService.JdProduct product = service.products("10159027099728").getFirst();

    assertTrue(product.externalUrl().startsWith("https://pro.m.jd.com/"));
  }

  @Test
  void buildsAccountDeeplinkPayload() {
    JdDeeplinkService service = new JdDeeplinkService(
        new RuntimeConfig(new ObjectMapper()), new ObjectMapper());
    JdDeeplinkService.JdProduct product = new JdDeeplinkService.JdProduct(
        "10159027099728", "test", "https://pro.m.jd.com/product");

    Map<String, Object> request = service.accountRequestBody(product, "1869943308174339", "4106412853");

    assertEquals(2, request.get("interface_version"));
    assertEquals("yinfu-qac-tt", request.get("account"));
    assertEquals("2038356894_4106198117_3107697823", request.get("pid"));
    assertEquals("ttyl2", request.get("channel"));
    assertEquals("https://pro.m.jd.com/product", request.get("lp_url"));
    assertEquals("1869943308174339", request.get("accountid"));
    assertEquals("4106412853", request.get("siteid"));
    assertEquals(List.of(Map.of("account_id", "1869943308174339", "site_id", "4106412853")),
        request.get("account_list"));
  }

  @Test
  void rejectsInvalidAccountIdentifiers() {
    JdDeeplinkService service = new JdDeeplinkService(
        new RuntimeConfig(new ObjectMapper()), new ObjectMapper());
    JdDeeplinkService.JdProduct product = new JdDeeplinkService.JdProduct(
        "1", "test", "https://pro.m.jd.com/product");

    assertThrows(IllegalArgumentException.class,
        () -> service.accountRequestBody(product, "", "4106412853"));
    assertThrows(IllegalArgumentException.class,
        () -> service.accountRequestBody(product, "abc", "4106412853"));
  }

  @Test
  void appliesCustomAccountRequestConfiguration() {
    JdDeeplinkService service = new JdDeeplinkService(
        new RuntimeConfig(new ObjectMapper()), new ObjectMapper());
    JdDeeplinkService.JdProduct product = new JdDeeplinkService.JdProduct(
        "1", "test", "https://pro.m.jd.com/product");
    JdDeeplinkService.AccountRequestConfig config = service.accountRequestConfig(
        "3", "custom-platform", "custom-account", "custom-pid", "custom-channel");

    Map<String, Object> request = service.accountRequestBody(product, "123", "456", config);

    assertEquals(3, request.get("interface_version"));
    assertEquals("custom-platform", request.get("platform"));
    assertEquals("custom-account", request.get("account"));
    assertEquals("custom-pid", request.get("pid"));
    assertEquals("custom-channel", request.get("channel"));
  }
}
