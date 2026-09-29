package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/memos")
public class MemoApiController {
  private final MemoService memos;
  private final SessionService sessions;
  public MemoApiController(MemoService memos, SessionService sessions) { this.memos = memos; this.sessions = sessions; }

  private long owner(HttpServletRequest request) {
    var user = sessions.currentUser(request);
    if (user == null || !user.active()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    return user.id();
  }

  @GetMapping
  public Map<String, Object> list(HttpServletRequest request,
      @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String tag,
      @RequestParam(defaultValue="all") String view, @RequestParam(defaultValue="0") int offset) throws SQLException {
    return memos.list(owner(request), q, tag, view, offset);
  }
  @GetMapping("/meta")
  public Map<String, Object> meta(HttpServletRequest request) throws SQLException { return memos.meta(owner(request)); }
  @GetMapping("/{id}")
  public Map<String, Object> get(HttpServletRequest request, @PathVariable long id) throws SQLException { return memos.get(owner(request), id); }
  @PostMapping
  public Map<String, Object> create(HttpServletRequest request, @RequestBody Map<String, Object> payload) throws SQLException {
    return memos.save(owner(request), null, payload);
  }
  @PutMapping("/{id}")
  public Map<String, Object> update(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> payload) throws SQLException {
    return memos.save(owner(request), id, payload);
  }
  @DeleteMapping("/{id}")
  public Map<String, Object> delete(HttpServletRequest request, @PathVariable long id, @RequestParam long version) throws SQLException {
    memos.trash(owner(request), id, version, true); return Map.of("ok", true);
  }
  @PostMapping("/{id}/restore")
  public Map<String, Object> restore(HttpServletRequest request, @PathVariable long id, @RequestBody Map<String, Object> payload) throws SQLException {
    memos.trash(owner(request), id, MemoService.version(payload.get("version")), false); return Map.of("ok", true);
  }
}
