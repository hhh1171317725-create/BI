package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

class GomokuRoomServiceTest {
  private final MutableClock clock = new MutableClock();
  private final GomokuRoomService service = new GomokuRoomService(clock, 256, 8, 8);
  private final UserRepository.UserAccount black = actor(1);
  private final UserRepository.UserAccount white = actor(2);
  private final String blackClient = UUID.randomUUID().toString();
  private final String whiteClient = UUID.randomUUID().toString();

  @Test
  void createJoinNormalizeAndRetryWithoutDuplicateRoomsOrExposingSecrets() {
    var created = service.create(black, blackClient);
    assertEquals(1, created.seat());
    assertEquals("waiting", created.phase());
    assertTrue(created.code().matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}"));
    var retried = service.create(black, blackClient);
    assertEquals(created.code(), retried.code());
    assertEquals(created.version(), retried.version());
    var joined = service.join(white, whiteClient, created.code().toLowerCase());
    assertEquals(2, joined.seat());
    assertEquals("playing", joined.phase());
    assertEquals(2, joined.players().size());
    assertEquals(joined.version(), service.join(white, whiteClient, created.code()).version());
    expect(HttpStatus.CONFLICT, () -> service.join(actor(3), UUID.randomUUID().toString(), created.code()));
    assertFalse(joined.toString().contains(blackClient));
    assertFalse(joined.toString().contains(whiteClient));
  }

  @Test
  void sameAccountWithSeparateClientIdsCanPlayAndDoesNotConsumeAnotherRoomAllowance() {
    var limited = new GomokuRoomService(clock, 2, 1, 8);
    var room = limited.create(black, blackClient);
    room = limited.join(black, whiteClient, room.code());
    assertEquals(2, room.seat());
    String code = room.code();
    var moved = limited.move(black, blackClient, code, 7, 7, room.version());
    assertEquals(1, moved.game().moves().size());
    assertEquals(2, limited.move(black, whiteClient, code, 7, 8, moved.version()).game().moves().size());
  }

  @Test
  void authenticationClientBindingAndMembershipProtectEveryOperation() {
    expect(HttpStatus.UNAUTHORIZED, () -> service.create(null, blackClient));
    expect(HttpStatus.BAD_REQUEST, () -> service.create(black, null));
    expect(HttpStatus.BAD_REQUEST, () -> service.create(black, "1-1-1-1-1"));
    var room = playing();
    expect(HttpStatus.UNAUTHORIZED, () -> service.join(null, blackClient, room.code()));
    expect(HttpStatus.UNAUTHORIZED, () -> service.read(null, blackClient, room.code(), -2, true));
    expect(HttpStatus.UNAUTHORIZED, () -> service.move(null, blackClient, room.code(), null, null, null));
    expect(HttpStatus.UNAUTHORIZED, () -> service.request(null, blackClient, room.code(), null, null));
    expect(HttpStatus.UNAUTHORIZED, () -> service.respond(null, blackClient, room.code(), null, null));
    expect(HttpStatus.UNAUTHORIZED, () -> service.leave(null, blackClient, room.code()));
    var outsider = actor(3);
    String outsiderClient = UUID.randomUUID().toString();
    service.create(outsider, outsiderClient);
    expect(HttpStatus.BAD_REQUEST, () -> service.create(outsider, blackClient));
    expect(HttpStatus.FORBIDDEN, () -> service.read(black, UUID.randomUUID().toString(), room.code(), -1, false));
    expect(HttpStatus.FORBIDDEN, () -> service.read(outsider, outsiderClient, room.code(), -1, true));
    expect(HttpStatus.FORBIDDEN, () -> service.move(outsider, outsiderClient, room.code(), 0, 0, room.version()));
    expect(HttpStatus.FORBIDDEN, () -> service.request(outsider, outsiderClient, room.code(), "restart", room.version()));
    expect(HttpStatus.FORBIDDEN, () -> service.respond(outsider, outsiderClient, room.code(), true, room.version()));
    expect(HttpStatus.FORBIDDEN, () -> service.leave(outsider, outsiderClient, room.code()));
  }

  @Test
  void validatesTurnOccupiedBoundsVersionWaitingAndCopiesBoard() {
    var waiting = service.create(black, blackClient);
    expect(HttpStatus.CONFLICT, () -> service.move(black, blackClient, waiting.code(), 0, 0, waiting.version()));
    expect(HttpStatus.CONFLICT, () -> service.request(black, blackClient, waiting.code(), "restart", waiting.version()));
    var joined = service.join(white, whiteClient, waiting.code());
    expect(HttpStatus.CONFLICT, () -> service.move(white, whiteClient, joined.code(), 0, 0, joined.version()));
    expect(HttpStatus.BAD_REQUEST, () -> service.move(black, blackClient, joined.code(), -1, 0, joined.version()));
    expect(HttpStatus.BAD_REQUEST, () -> service.move(black, blackClient, joined.code(), 0, 15, joined.version()));
    expect(HttpStatus.BAD_REQUEST, () -> service.move(black, blackClient, joined.code(), null, 0, joined.version()));
    expect(HttpStatus.BAD_REQUEST, () -> service.move(black, blackClient, joined.code(), 0, 0, null));
    var moved = service.move(black, blackClient, joined.code(), 0, 0, joined.version());
    expect(HttpStatus.CONFLICT, () -> service.move(white, whiteClient, joined.code(), 1, 1, joined.version()));
    expect(HttpStatus.CONFLICT, () -> service.move(white, whiteClient, joined.code(), 0, 0, moved.version()));
    moved.game().board()[0][0] = 0;
    assertEquals(1, snapshot(joined.code(), black, blackClient).game().board()[0][0]);
    assertThrows(UnsupportedOperationException.class, () -> moved.game().moves().clear());
  }

  @Test
  void detectsAllFourDirectionsAndLongLinesAndRejectsTerminalMoves() {
    for (int[] direction : new int[][] {{0, 1}, {1, 0}, {1, 1}, {1, -1}}) {
      GomokuRoomService local = new GomokuRoomService(clock, 2, 8, 8);
      var state = local.create(black, blackClient);
      state = local.join(white, whiteClient, state.code());
      for (int index = 0; index < 5; index++) {
        state = local.move(black, blackClient, state.code(),
            3 + index * direction[0], 7 + index * direction[1], state.version());
        if (index < 4) state = local.move(white, whiteClient, state.code(), 14, index * 2, state.version());
      }
      assertEquals("won", state.phase());
      assertEquals("won", state.game().status());
      assertEquals(1, state.game().winner());
      assertEquals(5, state.game().winningLine().size());
      var terminal = state;
      expect(HttpStatus.CONFLICT, () -> local.move(white, whiteClient, terminal.code(), 0, 0, terminal.version()));
    }
    var state = playing();
    int[] order = {0, 1, 3, 4, 5, 2};
    for (int index = 0; index < order.length; index++) {
      state = service.move(black, blackClient, state.code(), 5, order[index], state.version());
      if (index < order.length - 1) state = service.move(white, whiteClient, state.code(), 14, index * 2, state.version());
    }
    assertEquals("won", state.phase());
    assertEquals(6, state.game().winningLine().size());
  }

  @Test
  void fullBoardWithoutFiveInARowIsADraw() {
    var state = playing();
    List<GomokuRoomService.Point> blackCells = new ArrayList<>();
    List<GomokuRoomService.Point> whiteCells = new ArrayList<>();
    for (int row = 0; row < 15; row++) {
      for (int col = 0; col < 15; col++) {
        (((row + col / 2) % 2 == 0) ? blackCells : whiteCells).add(new GomokuRoomService.Point(row, col));
      }
    }
    for (int index = 0; index < blackCells.size(); index++) {
      var cell = blackCells.get(index);
      state = service.move(black, blackClient, state.code(), cell.row(), cell.col(), state.version());
      if (index < whiteCells.size()) {
        cell = whiteCells.get(index);
        state = service.move(white, whiteClient, state.code(), cell.row(), cell.col(), state.version());
      }
    }
    assertEquals(225, state.game().moves().size());
    assertEquals("draw", state.phase());
    assertEquals("draw", state.game().status());
    assertNull(state.game().winner());
  }

  @Test
  void concurrentMovesAtTheSameVersionCommitExactlyOnce() throws Exception {
    var state = playing();
    var gate = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> { gate.await(); return attemptMove(state, 0); });
      var second = executor.submit(() -> { gate.await(); return attemptMove(state, 1); });
      gate.countDown();
      assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
    }
    var committed = snapshot(state.code(), black, blackClient);
    assertEquals(state.version() + 1, committed.version());
    assertEquals(1, committed.game().moves().size());
  }

  @Test
  void undoNeedsConsentAndRemovesRequesterMoveAndLaterOpponentMove() {
    var state = playing();
    var empty = state;
    expect(HttpStatus.CONFLICT, () -> service.request(black, blackClient, empty.code(), "undo", empty.version()));
    state = service.move(black, blackClient, state.code(), 0, 0, state.version());
    state = service.move(white, whiteClient, state.code(), 1, 0, state.version());
    state = service.request(black, blackClient, state.code(), "undo", state.version());
    assertEquals("undo", state.request().type());
    assertEquals(1, state.request().by());
    var pending = state;
    expect(HttpStatus.CONFLICT, () -> service.move(black, blackClient, pending.code(), 0, 1, pending.version()));
    expect(HttpStatus.FORBIDDEN, () -> service.respond(black, blackClient, pending.code(), true, pending.version()));
    expect(HttpStatus.CONFLICT, () -> service.request(white, whiteClient, pending.code(), "restart", pending.version()));
    state = service.respond(white, whiteClient, state.code(), true, state.version());
    assertEquals(0, state.game().moves().size());
    assertEquals(1, state.game().currentPlayer());
    assertNull(state.request());
    state = service.move(black, blackClient, state.code(), 0, 0, state.version());
    state = service.move(white, whiteClient, state.code(), 1, 0, state.version());
    state = service.request(white, whiteClient, state.code(), "undo", state.version());
    state = service.respond(black, blackClient, state.code(), true, state.version());
    assertEquals(1, state.game().moves().size());
    assertEquals(2, state.game().currentPlayer());
  }

  @Test
  void rejectedRequestKeepsMovesAndAcceptedRestartResetsGameWithSameSeats() {
    var state = playing();
    state = service.move(black, blackClient, state.code(), 0, 0, state.version());
    state = service.request(black, blackClient, state.code(), "restart", state.version());
    var pending = state;
    expect(HttpStatus.BAD_REQUEST, () -> service.respond(white, whiteClient, pending.code(), null, pending.version()));
    state = service.respond(white, whiteClient, state.code(), false, state.version());
    assertEquals(1, state.game().moves().size());
    assertNull(state.request());
    state = service.request(white, whiteClient, state.code(), "restart", state.version());
    state = service.respond(black, blackClient, state.code(), true, state.version());
    assertEquals(0, state.game().moves().size());
    assertEquals("playing", state.phase());
    assertEquals(1, state.game().currentPlayer());
    assertEquals(1, snapshot(state.code(), black, blackClient).seat());
    assertEquals(2, snapshot(state.code(), white, whiteClient).seat());
  }

  @Test
  void acceptingUndoAfterWinReopensGameAndClearsWinnerAndLine() {
    var state = playing();
    for (int index = 0; index < 5; index++) {
      state = service.move(black, blackClient, state.code(), 5, index, state.version());
      if (index < 4) state = service.move(white, whiteClient, state.code(), 14, index * 2, state.version());
    }
    state = service.request(black, blackClient, state.code(), "undo", state.version());
    state = service.respond(white, whiteClient, state.code(), true, state.version());
    assertEquals("playing", state.phase());
    assertEquals("playing", state.game().status());
    assertNull(state.game().winner());
    assertTrue(state.game().winningLine().isEmpty());
    assertEquals(8, state.game().moves().size());
    assertEquals(1, state.game().currentPlayer());
  }

  @Test
  void pollsArePersonalizedWakeOnChangesAndLeaveIsIdempotent() {
    var state = playing();
    var blackPoll = service.read(black, blackClient, state.code(), state.version(), true);
    var whitePoll = service.read(white, whiteClient, state.code(), state.version(), true);
    assertFalse(blackPoll.hasResult());
    service.move(black, blackClient, state.code(), 3, 3, state.version());
    assertEquals(1, result(blackPoll).seat());
    assertEquals(2, result(whitePoll).seat());
    assertEquals(state.version() + 1, result(blackPoll).version());
    var pending = service.read(white, whiteClient, state.code(), result(whitePoll).version(), true);
    var closed = service.leave(black, blackClient, state.code());
    assertEquals("closed", result(pending).phase());
    assertFalse(result(pending).players().getFirst().online());
    assertEquals(closed.version(), service.leave(black, blackClient, state.code()).version());
    assertEquals("closed", service.join(white, whiteClient, state.code()).phase());
    expect(HttpStatus.CONFLICT, () -> service.move(white, whiteClient, state.code(), 1, 1, closed.version()));
  }

  @Test
  void futureVersionPollWaitsForNewerVersionAndTimeoutShowsFreshPresence() {
    var state = playing();
    var future = service.read(black, blackClient, state.code(), state.version() + 1, true);
    state = service.move(black, blackClient, state.code(), 0, 0, state.version());
    assertFalse(future.hasResult());
    state = service.move(white, whiteClient, state.code(), 1, 0, state.version());
    assertTrue(future.hasResult());
    var timeout = service.read(black, blackClient, state.code(), state.version(), true);
    clock.advance(46_000);
    service.cleanup();
    assertEquals(state.version(), result(timeout).version());
    assertFalse(result(timeout).players().getFirst().online());
    assertFalse(result(timeout).players().getLast().online());
    assertTrue(snapshot(state.code(), black, blackClient).players().getFirst().online());
  }

  @Test
  void limitsPollsAndRemovesCompletedAndTimedOutListeners() {
    var bounded = new GomokuRoomService(clock, 2, 2, 1);
    var state = bounded.create(black, blackClient);
    state = bounded.join(white, whiteClient, state.code());
    var current = state;
    var first = bounded.read(black, blackClient, state.code(), state.version(), true);
    expect(HttpStatus.TOO_MANY_REQUESTS, () -> bounded.read(white, whiteClient, current.code(), current.version(), true));
    first.setResult(current);
    var second = bounded.read(white, whiteClient, state.code(), state.version(), true);
    assertFalse(second.hasResult());
    clock.advance(20_000);
    bounded.cleanup();
    assertTrue(second.hasResult());
    assertFalse(bounded.read(black, blackClient, state.code(), state.version(), true).hasResult());
  }

  @Test
  void boundsOpenRoomsAndExpiredRoomsReleaseCapacityAndWakeListeners() {
    var bounded = new GomokuRoomService(clock, 2, 1, 8);
    var first = bounded.create(black, blackClient);
    expect(HttpStatus.TOO_MANY_REQUESTS, () -> bounded.create(black, UUID.randomUUID().toString()));
    var second = bounded.create(white, whiteClient);
    expect(HttpStatus.TOO_MANY_REQUESTS, () -> bounded.create(actor(3), UUID.randomUUID().toString()));
    var waiting = bounded.read(black, blackClient, first.code(), first.version(), true);
    clock.advance(2 * 60 * 60 * 1000L);
    bounded.cleanup();
    assertEquals("closed", result(waiting).phase());
    assertNotNull(result(waiting).closedReason());
    expect(HttpStatus.NOT_FOUND, () -> bounded.read(white, whiteClient, second.code(), -1, false));
    assertEquals("waiting", bounded.create(black, blackClient).phase());
  }

  @Test
  void closedRoomCanBeEvictedToKeepStorageBounded() {
    var bounded = new GomokuRoomService(clock, 1, 1, 8);
    var first = bounded.create(black, blackClient);
    bounded.leave(black, blackClient, first.code());
    var replacement = bounded.create(white, whiteClient);
    assertNotEquals(first.code(), replacement.code());
    expect(HttpStatus.NOT_FOUND, () -> bounded.read(black, blackClient, first.code(), -1, false));
  }

  private boolean attemptMove(GomokuRoomService.Snapshot state, int col) {
    try { service.move(black, blackClient, state.code(), 0, col, state.version()); return true; }
    catch (ResponseStatusException error) { assertEquals(HttpStatus.CONFLICT, error.getStatusCode()); return false; }
  }

  private GomokuRoomService.Snapshot playing() {
    var room = service.create(black, blackClient);
    return service.join(white, whiteClient, room.code());
  }

  private GomokuRoomService.Snapshot snapshot(String code, UserRepository.UserAccount actor, String client) {
    return result(service.read(actor, client, code, -1, false));
  }

  private static GomokuRoomService.Snapshot result(DeferredResult<GomokuRoomService.Snapshot> poll) {
    assertTrue(poll.hasResult());
    return (GomokuRoomService.Snapshot) poll.getResult();
  }

  private static void expect(HttpStatus status, Runnable action) {
    assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatusCode());
  }

  private static UserRepository.UserAccount actor(long id) {
    var now = LocalDateTime.of(2026, 10, 9, 18, 0);
    return new UserRepository.UserAccount(id, "player-" + id, "hash", "user", true, 1, now, now, now);
  }

  private static final class MutableClock extends Clock {
    private long timestamp = Instant.parse("2026-10-09T10:00:00Z").toEpochMilli();
    void advance(long duration) { timestamp += duration; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(timestamp); }
    @Override public long millis() { return timestamp; }
  }
}
