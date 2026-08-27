package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MailDingtalkServiceTest {
  @Test
  void extractsIdsFromTikTokMailVariants() {
    assertEquals(List.of("1874489175001681", "1874489177329426", "1874489177202385"),
        MailDingtalkService.extractAdGroupIds(
            "Ad Group ID: 1874489175001681,1874489177329426,1874489177202385 Creative ID: 123"));
    assertEquals(List.of("1874489175001681", "1874489177329426"),
        MailDingtalkService.extractAdGroupIds(
            "Ad\u00a0Group\u00a0IDs：1874489175001681， 1874489177329426\nTraining videos"));
    assertEquals(List.of("1874489175001681"),
        MailDingtalkService.extractAdGroupIds("Campaign\u200B ID - 1874489175001681"));
  }
}
