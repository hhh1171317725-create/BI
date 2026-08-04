package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JdImageServiceTest {
  private final JdImageService service = new JdImageService(new ObjectMapper());

  @Test
  void parsesProductImagesFromMobilePageData() throws Exception {
    String html = "<script>window._itemOnly = ({\"item\":{\"skuId\":\"10105896126923\","
        + "\"skuName\":\"test } product\",\"image\":[\"jfs/t1/a.png\",\"jfs/t1/b.jpg\"]}});"
        + "window._isLogin='0';</script>";

    JdImageService.ProductInfo product = service.parseProduct("10105896126923", html);

    assertEquals("test } product", product.name());
    assertEquals(List.of(
        "https://img10.360buyimg.com/n0/jfs/t1/a.png",
        "https://img10.360buyimg.com/n0/jfs/t1/b.jpg"), product.images());
  }

  @Test
  void normalizesAndDeduplicatesSkuInput() {
    assertEquals(List.of("10105896126923", "12345"),
        JdImageService.normalizeSkus(List.of("SKU: 10105896126923", "10105896126923, 12345")));
  }

  @Test
  void onlyAcceptsJdImageHosts() {
    assertEquals("https://img10.360buyimg.com/n0/jfs/t1/a.jpg",
        JdImageService.normalizeImageUrl("//img10.360buyimg.com/s800x800_jfs/t1/a.jpg"));
    assertEquals("", JdImageService.normalizeImageUrl("https://example.com/a.jpg"));
  }

  @Test
  void rejectsMoreThanTwentySkus() {
    List<String> values = java.util.stream.IntStream.range(10000, 10021)
        .mapToObj(String::valueOf).toList();
    assertThrows(IllegalArgumentException.class, () -> JdImageService.normalizeSkus(values));
  }
}
