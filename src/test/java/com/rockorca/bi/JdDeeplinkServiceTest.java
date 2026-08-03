package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
