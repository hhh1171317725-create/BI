package com.rockorca.bi;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jd-deeplink")
public class JdDeeplinkApiController {
  private final JdDeeplinkService deeplinks;

  public JdDeeplinkApiController(JdDeeplinkService deeplinks) {
    this.deeplinks = deeplinks;
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> payload) {
    return deeplinks.create(ReportService.text(payload.get("lpUrl")));
  }
}
