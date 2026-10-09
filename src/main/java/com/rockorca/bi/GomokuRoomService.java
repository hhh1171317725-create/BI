package com.rockorca.bi;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/** Authoritative, bounded, in-memory rooms. Restarting the server ends all rooms. */
@Service
public class GomokuRoomService {
  private static final int SIZE = 15;
  private static final long EXPIRY_MS = Duration.ofHours(2).toMillis();
  private static final long ONLINE_MS = Duration.ofSeconds(45).toMillis();
  private static final long POLL_MS = Duration.ofSeconds(20).toMillis();
  private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private final Object registryLock = new Object();
  private final Map<String, Room> rooms = new LinkedHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final Clock clock;
  private final int maxRooms;
  private final int maxActorRooms;
  private final int maxWatchers;

  public GomokuRoomService() {
    this(Clock.systemUTC(), 256, 8, 8);
  }

  GomokuRoomService(Clock clock, int maxRooms, int maxActorRooms, int maxWatchers) {
    this.clock = clock;
    this.maxRooms = maxRooms;
    this.maxActorRooms = maxActorRooms;
    this.maxWatchers = maxWatchers;
  }

  public Snapshot create(UserRepository.UserAccount actor, String clientValue) {
    String client = client(actor, clientValue);
    synchronized (registryLock) {
      cleanupLocked();
      validateClientBinding(actor, client);
      for (Room room : rooms.values()) {
        synchronized (room) {
          if (!room.closed() && room.black.matches(actor.id(), client)) {
            touch(room, room.black);
            return snapshot(room, 1);
          }
        }
      }
      requireActorCapacity(actor.id());
      evictClosedRooms();
      if (rooms.size() >= maxRooms) throw error(HttpStatus.TOO_MANY_REQUESTS, "房间已满，请稍后再试");
      String code;
      do { code = newCode(); } while (rooms.containsKey(code));
      Room room = new Room(code, new Participant(actor.id(), client, actor.username(), 1, now()), now());
      rooms.put(code, room);
      return snapshot(room, 1);
    }
  }

  public Snapshot join(UserRepository.UserAccount actor, String clientValue, String codeValue) {
    String client = client(actor, clientValue);
    String code = code(codeValue);
    synchronized (registryLock) {
      cleanupLocked();
      validateClientBinding(actor, client);
      Room room = requiredRoom(code);
      synchronized (room) {
        Participant existing = memberOrNull(room, actor.id(), client);
        if (existing != null) {
          touch(room, existing);
          return snapshot(room, existing.seat);
        }
        requireOpen(room);
        if (room.white != null) throw error(HttpStatus.CONFLICT, "房间已有两位玩家");
        if (room.black.actorId != actor.id()) requireActorCapacity(actor.id());
        room.white = new Participant(actor.id(), client, actor.username(), 2, now());
        room.phase = "playing";
        touch(room, room.white);
        changed(room);
        return snapshot(room, 2);
      }
    }
  }

  public DeferredResult<Snapshot> read(
      UserRepository.UserAccount actor, String clientValue, String codeValue, long since, boolean wait) {
    client(actor, clientValue);
    if (since < -1) throw error(HttpStatus.BAD_REQUEST, "版本号无效");
    Room room = find(actor, clientValue, codeValue);
    synchronized (room) {
      Participant participant = member(room, actor.id(), client(actor, clientValue));
      touch(room, participant);
      DeferredResult<Snapshot> result = new DeferredResult<>(POLL_MS);
      if (!wait || room.version > since || room.closed()) {
        result.setResult(snapshot(room, participant.seat));
        return result;
      }
      removeFinishedWatchers(room);
      if (room.watchers.size() >= maxWatchers) {
        throw error(HttpStatus.TOO_MANY_REQUESTS, "房间等待连接过多，请稍后再试");
      }
      Watcher watcher = new Watcher(result, participant.seat, since, now());
      room.watchers.add(watcher);
      result.onTimeout(() -> completePoll(room, watcher));
      result.onCompletion(() -> removeWatcher(room, watcher));
      result.onError(error -> removeWatcher(room, watcher));
      return result;
    }
  }

  public Snapshot move(UserRepository.UserAccount actor, String clientValue, String codeValue,
      Integer row, Integer col, Long version) {
    client(actor, clientValue);
    if (row == null || col == null || row < 0 || row >= SIZE || col < 0 || col >= SIZE) {
      throw error(HttpStatus.BAD_REQUEST, "落子位置无效");
    }
    Room room = find(actor, clientValue, codeValue);
    synchronized (room) {
      Participant participant = member(room, actor.id(), client(actor, clientValue));
      requireVersion(room, version);
      requireOpen(room);
      if (!"playing".equals(room.phase)) throw error(HttpStatus.CONFLICT, "当前无法落子");
      if (room.request != null) throw error(HttpStatus.CONFLICT, "请先处理对方的确认请求");
      if (participant.seat != room.currentPlayer) throw error(HttpStatus.CONFLICT, "还未轮到你落子");
      if (room.board[row][col] != 0) throw error(HttpStatus.CONFLICT, "此位置已有棋子");
      room.board[row][col] = participant.seat;
      room.moves.add(new Move(row, col, participant.seat));
      room.currentPlayer = 3 - participant.seat;
      List<Point> line = winningLine(room.board, row, col, participant.seat);
      if (!line.isEmpty()) {
        room.phase = "won";
        room.status = "won";
        room.winner = participant.seat;
        room.winningLine = line;
      } else if (room.moves.size() == SIZE * SIZE) {
        room.phase = "draw";
        room.status = "draw";
      }
      touch(room, participant);
      changed(room);
      return snapshot(room, participant.seat);
    }
  }

  public Snapshot request(UserRepository.UserAccount actor, String clientValue, String codeValue,
      String type, Long version) {
    client(actor, clientValue);
    if (!"undo".equals(type) && !"restart".equals(type)) {
      throw error(HttpStatus.BAD_REQUEST, "请求类型无效");
    }
    Room room = find(actor, clientValue, codeValue);
    synchronized (room) {
      Participant participant = member(room, actor.id(), client(actor, clientValue));
      requireVersion(room, version);
      requireOpen(room);
      if (room.white == null) throw error(HttpStatus.CONFLICT, "请等待另一位玩家加入");
      if (room.request != null) throw error(HttpStatus.CONFLICT, "已有待确认的请求");
      if ("undo".equals(type) && room.moves.stream().noneMatch(move -> move.player() == participant.seat)) {
        throw error(HttpStatus.CONFLICT, "你还没有可以撤回的落子");
      }
      room.request = new Consent(type, participant.seat);
      touch(room, participant);
      changed(room);
      return snapshot(room, participant.seat);
    }
  }

  public Snapshot respond(UserRepository.UserAccount actor, String clientValue, String codeValue,
      Boolean accept, Long version) {
    client(actor, clientValue);
    if (accept == null) throw error(HttpStatus.BAD_REQUEST, "请提供是否同意");
    Room room = find(actor, clientValue, codeValue);
    synchronized (room) {
      Participant participant = member(room, actor.id(), client(actor, clientValue));
      requireVersion(room, version);
      requireOpen(room);
      if (room.request == null) throw error(HttpStatus.CONFLICT, "没有待确认的请求");
      if (room.request.by() == participant.seat) throw error(HttpStatus.FORBIDDEN, "只有对方可以处理此请求");
      if (accept) {
        if ("restart".equals(room.request.type())) room.moves.clear();
        else {
          for (int index = room.moves.size() - 1; index >= 0; index--) {
            if (room.moves.get(index).player() == room.request.by()) {
              room.moves.subList(index, room.moves.size()).clear();
              break;
            }
          }
        }
        rebuild(room);
      }
      room.request = null;
      touch(room, participant);
      changed(room);
      return snapshot(room, participant.seat);
    }
  }

  public Snapshot leave(UserRepository.UserAccount actor, String clientValue, String codeValue) {
    Room room = find(actor, clientValue, codeValue);
    synchronized (room) {
      Participant participant = member(room, actor.id(), client(actor, clientValue));
      if (!room.closed()) {
        touch(room, participant);
        close(room, "玩家已离开房间");
      }
      return snapshot(room, participant.seat);
    }
  }

  @Scheduled(fixedDelay = 30_000)
  public void cleanup() {
    synchronized (registryLock) { cleanupLocked(); }
  }

  private Room find(UserRepository.UserAccount actor, String clientValue, String codeValue) {
    String client = client(actor, clientValue);
    String code = code(codeValue);
    synchronized (registryLock) {
      cleanupLocked();
      Room room = requiredRoom(code);
      validateClientBinding(actor, client);
      return room;
    }
  }

  private Room requiredRoom(String code) {
    Room room = rooms.get(code);
    if (room == null) throw error(HttpStatus.NOT_FOUND, "房间不存在或已过期");
    return room;
  }

  // registryLock always precedes a room monitor; room operations never acquire registryLock.
  private void cleanupLocked() {
    long timestamp = now();
    Iterator<Room> iterator = rooms.values().iterator();
    while (iterator.hasNext()) {
      Room room = iterator.next();
      synchronized (room) {
        if (timestamp - room.lastActivity >= EXPIRY_MS) {
          if (!room.closed()) close(room, "房间长时间未使用，已结束");
          else notifyWatchers(room);
          room.retired = true;
          iterator.remove();
        } else {
          removeFinishedWatchers(room);
          for (Watcher watcher : List.copyOf(room.watchers)) {
            if (timestamp - watcher.createdAt >= POLL_MS) completePoll(room, watcher);
          }
        }
      }
    }
  }

  private void evictClosedRooms() {
    if (rooms.size() < maxRooms) return;
    Iterator<Room> iterator = rooms.values().iterator();
    while (iterator.hasNext() && rooms.size() >= maxRooms) {
      Room room = iterator.next();
      synchronized (room) {
        if (room.closed()) {
          notifyWatchers(room);
          room.retired = true;
          iterator.remove();
        }
      }
    }
  }

  private void validateClientBinding(UserRepository.UserAccount actor, String client) {
    for (Room room : rooms.values()) {
      synchronized (room) {
        if ((room.black.client.equals(client) && room.black.actorId != actor.id())
            || (room.white != null && room.white.client.equals(client) && room.white.actorId != actor.id())) {
          throw error(HttpStatus.BAD_REQUEST, "客户端标识与登录账号不匹配");
        }
      }
    }
  }

  private void requireActorCapacity(long actorId) {
    int count = 0;
    for (Room room : rooms.values()) {
      synchronized (room) {
        if (!room.closed() && (room.black.actorId == actorId || (room.white != null && room.white.actorId == actorId))) count++;
      }
    }
    if (count >= maxActorRooms) throw error(HttpStatus.TOO_MANY_REQUESTS, "参与的房间过多，请先结束旧房间");
  }

  private static Participant member(Room room, long actorId, String client) {
    if (room.retired) throw error(HttpStatus.NOT_FOUND, "房间不存在或已过期");
    Participant participant = memberOrNull(room, actorId, client);
    if (participant == null) throw error(HttpStatus.FORBIDDEN, "你不是此房间的玩家");
    return participant;
  }

  private static Participant memberOrNull(Room room, long actorId, String client) {
    if (room.black.matches(actorId, client)) return room.black;
    return room.white != null && room.white.matches(actorId, client) ? room.white : null;
  }

  private void touch(Room room, Participant participant) {
    participant.lastSeen = now();
    room.lastActivity = now();
  }

  private void changed(Room room) {
    room.version++;
    notifyWatchers(room);
  }

  private void close(Room room, String reason) {
    room.phase = "closed";
    room.closedReason = reason;
    room.request = null;
    changed(room);
  }

  private void notifyWatchers(Room room) {
    List<Watcher> waiting = List.copyOf(room.watchers);
    for (Watcher watcher : waiting) {
      if (room.closed() || room.version > watcher.since) {
        room.watchers.remove(watcher);
        watcher.result.setResult(snapshot(room, watcher.seat));
      }
    }
  }

  private void completePoll(Room room, Watcher watcher) {
    synchronized (room) {
      room.watchers.remove(watcher);
      watcher.result.setResult(snapshot(room, watcher.seat));
    }
  }

  private static void removeWatcher(Room room, Watcher watcher) {
    synchronized (room) { room.watchers.remove(watcher); }
  }

  private static void removeFinishedWatchers(Room room) {
    room.watchers.removeIf(watcher -> watcher.result.isSetOrExpired());
  }

  private Snapshot snapshot(Room room, int seat) {
    int[][] board = new int[SIZE][];
    for (int row = 0; row < SIZE; row++) board[row] = room.board[row].clone();
    List<Player> players = new ArrayList<>();
    players.add(player(room, room.black));
    if (room.white != null) players.add(player(room, room.white));
    return new Snapshot(room.code, room.version, seat, room.phase,
        new Game(board, List.copyOf(room.moves), room.currentPlayer, room.status, room.winner,
            List.copyOf(room.winningLine)), List.copyOf(players), room.request, room.closedReason);
  }

  private Player player(Room room, Participant participant) {
    return new Player(participant.seat, participant.name,
        !room.closed() && now() - participant.lastSeen <= ONLINE_MS);
  }

  private static void rebuild(Room room) {
    room.board = new int[SIZE][SIZE];
    for (Move move : room.moves) room.board[move.row()][move.col()] = move.player();
    room.currentPlayer = room.moves.isEmpty() ? 1 : 3 - room.moves.getLast().player();
    room.phase = "playing";
    room.status = "playing";
    room.winner = null;
    room.winningLine = List.of();
  }

  private static List<Point> winningLine(int[][] board, int row, int col, int player) {
    for (int[] direction : new int[][] {{0, 1}, {1, 0}, {1, 1}, {1, -1}}) {
      int startRow = row;
      int startCol = col;
      while (inside(startRow - direction[0], startCol - direction[1])
          && board[startRow - direction[0]][startCol - direction[1]] == player) {
        startRow -= direction[0];
        startCol -= direction[1];
      }
      List<Point> line = new ArrayList<>();
      while (inside(startRow, startCol) && board[startRow][startCol] == player) {
        line.add(new Point(startRow, startCol));
        startRow += direction[0];
        startCol += direction[1];
      }
      if (line.size() >= 5) return List.copyOf(line);
    }
    return List.of();
  }

  private static boolean inside(int row, int col) {
    return row >= 0 && row < SIZE && col >= 0 && col < SIZE;
  }

  private static void requireVersion(Room room, Long version) {
    if (version == null || version < 0) throw error(HttpStatus.BAD_REQUEST, "请提供有效的版本号");
    if (room.version != version) throw error(HttpStatus.CONFLICT, "房间已更新，请刷新后重试");
  }

  private static void requireOpen(Room room) {
    if (room.closed()) throw error(HttpStatus.CONFLICT, "房间已结束");
  }

  private static String client(UserRepository.UserAccount actor, String value) {
    if (actor == null) throw error(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    if (value == null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
      throw error(HttpStatus.BAD_REQUEST, "请提供有效的客户端标识");
    }
    return UUID.fromString(value).toString();
  }

  private static String code(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (normalized.length() != 6 || normalized.chars().anyMatch(c -> CODE_ALPHABET.indexOf(c) < 0)) {
      throw error(HttpStatus.BAD_REQUEST, "房间码无效");
    }
    return normalized;
  }

  private String newCode() {
    StringBuilder code = new StringBuilder();
    for (int index = 0; index < 6; index++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
    return code.toString();
  }

  private long now() { return clock.millis(); }

  private static ResponseStatusException error(HttpStatus status, String reason) {
    return new ResponseStatusException(status, reason);
  }

  public record Point(int row, int col) {}
  public record Move(int row, int col, int player) {}
  public record Game(int[][] board, List<Move> moves, int currentPlayer, String status,
      Integer winner, List<Point> winningLine) {}
  public record Player(int seat, String name, boolean online) {}
  public record Consent(String type, int by) {}
  public record Snapshot(String code, long version, int seat, String phase, Game game,
      List<Player> players, Consent request, String closedReason) {}

  private static final class Participant {
    final long actorId;
    final String client;
    final String name;
    final int seat;
    long lastSeen;
    Participant(long actorId, String client, String name, int seat, long lastSeen) {
      this.actorId = actorId;
      this.client = client;
      this.name = name;
      this.seat = seat;
      this.lastSeen = lastSeen;
    }
    boolean matches(long id, String value) { return actorId == id && client.equals(value); }
  }

  private static final class Room {
    final String code;
    final Participant black;
    Participant white;
    long lastActivity;
    long version = 1;
    String phase = "waiting";
    String status = "playing";
    int currentPlayer = 1;
    Integer winner;
    String closedReason;
    Consent request;
    int[][] board = new int[SIZE][SIZE];
    final List<Move> moves = new ArrayList<>();
    List<Point> winningLine = List.of();
    final List<Watcher> watchers = new ArrayList<>();
    boolean retired;
    Room(String code, Participant black, long lastActivity) {
      this.code = code;
      this.black = black;
      this.lastActivity = lastActivity;
    }
    boolean closed() { return "closed".equals(phase); }
  }

  private record Watcher(DeferredResult<Snapshot> result, int seat, long since, long createdAt) {}
}
