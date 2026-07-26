package com.rockorca.bi;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 数据助手 API。模型凭据和底表访问始终留在后端。 */
@RestController
@RequestMapping("/api/pet")
public class PetApiController {
  private final PetService pets;

  public PetApiController(PetService pets) {
    this.pets = pets;
  }

  @PostMapping("/chat")
  public Map<String, Object> chat(@RequestBody Map<String, Object> payload) {
    return pets.chat(payload);
  }
}
