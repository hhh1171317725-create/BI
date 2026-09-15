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
  @GetMapping("/revision")
  public org.springframework.http.ResponseEntity<Map<String,String>> revision(){
    return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
        .body(Map.of("sourceRevision",gaps.sourceRevision()));
  }
}
