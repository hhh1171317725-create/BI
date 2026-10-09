package com.rockorca.bi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/** Cookie-authenticated remote Gomoku rooms, with a separate identity for each browser tab. */
@RestController
@RequestMapping("/api/games/gomoku/rooms")
public class GomokuApiController {
  private final GomokuRoomService rooms;
  private final SessionService sessions;

  public GomokuApiController(GomokuRoomService rooms, SessionService sessions) {
    this.rooms = rooms;
    this.sessions = sessions;
  }

  @PostMapping
  public GomokuRoomService.Snapshot create(
      @RequestHeader(value = "X-Game-Client", required = false) String client,
      HttpServletRequest request) {
    return rooms.create(currentUser(request), client);
  }

  @PostMapping("/{code}/join")
  public GomokuRoomService.Snapshot join(
      @PathVariable String code,
      @RequestHeader(value = "X-Game-Client", required = false) String client,
      HttpServletRequest request) {
    return rooms.join(currentUser(request), client, code);
  }

  @GetMapping("/{code}")
  public DeferredResult<GomokuRoomService.Snapshot> read(
      @PathVariable String code,
      @RequestHeader(value = "X-Game-Client", required = false) String client,
      @RequestParam(defaultValue = "-1") long since,
      @RequestParam(defaultValue = "false") boolean wait,
      HttpServletRequest request) {
    return rooms.read(currentUser(request), client, code, since, wait);
  }

  @PostMapping("/{code}/moves")
  public GomokuRoomService.Snapshot move(
      @PathVariable String code,
      @RequestHeader(value = "X-Game-Client", required = false) String client,
      @RequestBody MoveBody body,
      HttpServletRequest request) {
    return rooms.move(currentUser(request), client, code, body.row(), body.col(), body.version());
  }

  @PostMapping("/{code}/requests")
  public GomokuRoomService.Snapshot request(
      @PathVariable String code,
      @RequestHeader(value = "X-Game-Client", required = false) String client,
      @RequestBody RequestBodyPayload body,
      HttpServletRequest request) {
    return rooms.request(currentUser(request), client, code, body.type(), body.version());
  }

  @PostMapping("/{code}/requests/respond")
  public GomokuRoomService.Snapshot respond(
      @PathVariable String code,
      @RequestHeader(value = "X-Game-Client", required = false) String client,
      @RequestBody RespondBody body,
      HttpServletRequest request) {
    return rooms.respond(currentUser(request), client, code, body.accept(), body.version());
  }

  @PostMapping("/{code}/leave")
  public GomokuRoomService.Snapshot leave(
      @PathVariable String code,
      @RequestHeader(value = "X-Game-Client", required = false) String client,
      HttpServletRequest request) {
    return rooms.leave(currentUser(request), client, code);
  }

  private UserRepository.UserAccount currentUser(HttpServletRequest request) {
    UserRepository.UserAccount user = sessions.currentUser(request);
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return user;
  }

  public record MoveBody(Integer row, Integer col, Long version) {}

  public record RequestBodyPayload(String type, Long version) {}

  public record RespondBody(Boolean accept, Long version) {}
}
