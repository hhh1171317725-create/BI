package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/bid-monitor/shared-report")
public class BidSharedReportController {
  private final SessionService sessions;
  private final UserRepository accounts;
  private final UserService users;
  private final BidSnapshotController snapshots;
  private final BidServerSyncStore store;
  private final RuntimeConfig config;
  public BidSharedReportController(SessionService sessions,UserRepository accounts,UserService users,
      BidSnapshotController snapshots,BidServerSyncStore store,RuntimeConfig config){
    this.sessions=sessions;this.accounts=accounts;this.users=users;this.snapshots=snapshots;this.store=store;this.config=config;
  }

  synchronized UserRepository.UserAccount source() throws Exception {
    String configured=config.get("BID_SHARED_OWNER_ID", "");
    var admins=accounts.list().stream().filter(a->a.active()&&a.admin()).sorted(Comparator.comparingLong(UserRepository.UserAccount::id)).toList();
    if(!configured.isBlank()) return admins.stream().filter(a->Long.toString(a.id()).equals(configured)).findFirst()
        .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"共享来源管理员已停用，请管理员检查共享来源配置"));
    if(admins.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"没有可用的管理员维护共享报表");
    // Adopt existing data once, then persist the source so adding another admin cannot silently switch it.
    for(var admin:admins) if(snapshots.readOwned(admin.id()).get("rows") instanceof List<?> rows&&!rows.isEmpty()) {
      config.saveBidSharedOwner(admin.id());return admin;
    }
    for(var admin:admins) if(store.get(admin.id()).containsKey("credential")) {
      config.saveBidSharedOwner(admin.id());return admin;
    }
    var admin=admins.getFirst();config.saveBidSharedOwner(admin.id());return admin;
  }

  @GetMapping
  public Map<String,Object> get(HttpServletRequest request,@RequestParam(defaultValue="") String after) throws Exception {
    var viewer=sessions.currentUser(request);
    if(viewer==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!viewer.active()||!users.canUseTool(viewer,"bidMonitor"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    var owner=source();
    if(!users.canUseTool(owner,"bidMonitor"))throw new ResponseStatusException(HttpStatus.CONFLICT,"共享来源暂不可用，请联系管理员");
    var snapshot=snapshots.readOwned(owner.id());var state=store.get(owner.id());
    String version=owner.id()+":"+Objects.toString(snapshot.get("updatedAt"),"");
    var status=new LinkedHashMap<String,Object>();
    for(String key:List.of("enabled","state","lastSuccess","dueAt")) if(state.containsKey(key))status.put(key,state.get(key));
    boolean canManage=viewer.admin()&&viewer.id()==owner.id();
    return ReportService.mapOf("userId",Long.toString(viewer.id()),"sharedOwnerId",Long.toString(owner.id()),
        "sharedOwnerName",owner.username(),"canManage",canManage,"version",version,
        "snapshot",version.equals(after)?null:snapshot,"status",status,
        "rules",state.getOrDefault("taskRules",List.of()),"pricingRevision",state.getOrDefault("pricingRevision",""),
        "strategies",state.getOrDefault("strategies",List.of()),"strategyRevision",state.getOrDefault("strategyRevision",""));
  }
}
