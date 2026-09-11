package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/bid-monitor/history")
public class BidHistoryController {
  private final SessionService sessions;
  private final UserService users;
  private final BidSharedReportController shared;
  private final BidHistoryStore history;

  public BidHistoryController(SessionService sessions,UserService users,BidSharedReportController shared,BidHistoryStore history){
    this.sessions=sessions;this.users=users;this.shared=shared;this.history=history;
  }

  @GetMapping
  public Map<String,Object> get(HttpServletRequest request,@RequestParam String startDate,@RequestParam String endDate)throws Exception{
    var viewer=sessions.currentUser(request);
    if(viewer==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!viewer.active()||!users.canUseTool(viewer,"bidMonitor"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    LocalDate start=LocalDate.parse(startDate),end=LocalDate.parse(endDate),today=LocalDate.now(ReportService.BEIJING);
    if(start.isAfter(end)||start.plusDays(30).isBefore(end)||!end.isBefore(today))
      throw new IllegalArgumentException("历史时间范围须为昨天以前，且不超过 31 天");
    var owner=shared.source();
    if(!users.canUseTool(owner,"bidMonitor"))throw new ResponseStatusException(HttpStatus.CONFLICT,"共享来源暂不可用，请联系管理员");
    var rows=history.read(owner.id(),start,end);
    return Map.of("startDate",start.toString(),"endDate",end.toString(),"rows",rows,"count",rows.size());
  }
}
