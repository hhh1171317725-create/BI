package com.rockorca.bi;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bid-monitor/gap")
public class BidGapController {
  private final BidGapService gaps;
  public BidGapController(BidGapService gaps){this.gaps=gaps;}
  @GetMapping
  public Map<String,Object> get(@RequestParam String endDate){return gaps.load(endDate);}
}
