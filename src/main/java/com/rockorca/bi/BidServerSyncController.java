package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/bid-monitor/server-sync")
public class BidServerSyncController {
  private final SessionService sessions;
  private final BidServerSyncService service;
  public BidServerSyncController(SessionService sessions,BidServerSyncService service){this.sessions=sessions;this.service=service;}

  private long owner(HttpServletRequest request,Map<String,Object> input){
    var actor=sessions.currentUser(request);
    if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!service.allowed(actor.id()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if(input!=null&&!Long.toString(actor.id()).equals(String.valueOf(input.get("expectedUserId"))))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"网站账户已切换，请刷新页面");
    return actor.id();
  }
  @GetMapping public Map<String,Object> status(HttpServletRequest request)throws Exception{return service.status(owner(request,null));}
  @PostMapping("/start") public Map<String,Object> start(@RequestBody Map<String,Object> input,HttpServletRequest request)throws Exception{
    return service.start(owner(request,input),input);
  }
  @PostMapping("/{action:run|stop|forget}") public Map<String,Object> command(@PathVariable String action,@RequestBody Map<String,Object> input,HttpServletRequest request)throws Exception{
    return service.command(owner(request,input),action);
  }
}
