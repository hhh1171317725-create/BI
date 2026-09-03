package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/bid-monitor/dingtalk")
public class BidDingtalkController {
  private final SessionService sessions;
  private final BidServerSyncService sync;
  private final BidDingtalkService service;
  public BidDingtalkController(SessionService sessions,BidServerSyncService sync,BidDingtalkService service){this.sessions=sessions;this.sync=sync;this.service=service;}
  private long owner(HttpServletRequest request,Map<String,Object> input){
    var actor=sessions.currentUser(request);
    if(actor==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!sync.allowed(actor.id()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if(input!=null&&!Long.toString(actor.id()).equals(input.get("expectedUserId")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"网站账户已切换，请刷新");
    return actor.id();
  }
  @GetMapping public Map<String,Object> settings(HttpServletRequest request)throws Exception{return service.settings(owner(request,null));}
  @PostMapping public Map<String,Object> save(@RequestBody Map<String,Object> input,HttpServletRequest request)throws Exception{return service.save(owner(request,input),input);}
  @PostMapping("/preview") public Map<String,Object> preview(@RequestBody Map<String,Object> input,HttpServletRequest request)throws Exception{return service.preview(owner(request,input));}
  @PostMapping("/send") public Map<String,Object> send(@RequestBody Map<String,Object> input,HttpServletRequest request)throws Exception{return service.send(owner(request,input),false);}
  @PostMapping("/forget") public Map<String,Object> forget(@RequestBody Map<String,Object> input,HttpServletRequest request)throws Exception{return service.forget(owner(request,input));}
}
