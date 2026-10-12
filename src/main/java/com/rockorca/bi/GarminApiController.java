package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/garmin")
public class GarminApiController {
  private final SessionService sessions;
  private final UserService users;
  private final GarminSyncService service;
  public GarminApiController(SessionService sessions,UserService users,GarminSyncService service){
    this.sessions=sessions;this.users=users;this.service=service;
  }
  @ModelAttribute
  void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");}
  private long owner(HttpServletRequest request,Map<String,Object> input){
    var actor=sessions.currentUser(request);
    if(actor==null||!actor.active())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
    if(!users.canUseTool(actor,"garmin"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"佳明工具权限已关闭");
    if(input!=null)sameOrigin(request);
    if(input!=null&&!Long.toString(actor.id()).equals(String.valueOf(input.get("expectedUserId"))))throw new AccountChanged();
    return actor.id();
  }
  private static void sameOrigin(HttpServletRequest request){
    String site=request.getHeader("Sec-Fetch-Site"),origin=request.getHeader("Origin");
    if(site!=null&&!site.equals("same-origin")&&!site.equals("none"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请从本站页面操作佳明连接");
    if(origin==null)return;
    try{
      String scheme=request.isSecure()||"https".equals(request.getHeader("X-Forwarded-Proto"))?"https":"http";
      String host=request.getHeader("Host");if(host==null)host=request.getServerName()+":"+request.getServerPort();
      URI expected=URI.create(scheme+"://"+host),actual=URI.create(origin);
      if(actual.getUserInfo()!=null||actual.getQuery()!=null||actual.getFragment()!=null||actual.getHost()==null||!Objects.equals(expected.getHost(),actual.getHost())
          ||!scheme.equals(actual.getScheme())||port(expected)!=port(actual)||!Objects.toString(actual.getPath(),"").isEmpty())throw new IllegalArgumentException();
    }catch(Exception error){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请从本站页面操作佳明连接");}
  }
  private static int port(URI uri){return uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();}
  static final class AccountChanged extends RuntimeException {}
  @ExceptionHandler(AccountChanged.class)
  ResponseEntity<Map<String,Object>> changedAccount(){
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code","ACCOUNT_CHANGED","error","网站登录账户已更换，请刷新页面后继续"));
  }
  @GetMapping("/status") public Map<String,Object> status(HttpServletRequest request){return service.status(owner(request,null));}
  @GetMapping("/data") public Map<String,Object> data(HttpServletRequest request,
      @RequestParam(defaultValue="") String startDate,@RequestParam(defaultValue="") String endDate){
    return service.data(owner(request,null),startDate,endDate);
  }
  @PostMapping("/connect") public Map<String,Object> connect(HttpServletRequest request,@RequestBody Map<String,Object> input){
    return service.connect(owner(request,input),input);
  }
  @PostMapping("/mfa") public Map<String,Object> mfa(HttpServletRequest request,@RequestBody Map<String,Object> input){
    return service.mfa(owner(request,input),input);
  }
  @PostMapping("/sync") public Map<String,Object> sync(HttpServletRequest request,@RequestBody Map<String,Object> input){
    return service.sync(owner(request,input),input);
  }
  @PostMapping("/settings") public Map<String,Object> settings(HttpServletRequest request,@RequestBody Map<String,Object> input){
    return service.settings(owner(request,input),input);
  }
  @PostMapping("/disconnect") public Map<String,Object> disconnect(HttpServletRequest request,@RequestBody Map<String,Object> input){
    return service.disconnect(owner(request,input));
  }
}
