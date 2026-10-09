package com.rockorca.bi;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GomokuApiControllerTest {
  private static final String BASE = "/api/games/gomoku/rooms";
  private static final String HOST_CLIENT = "11111111-1111-4111-8111-111111111111";
  private static final String GUEST_CLIENT = "22222222-2222-4222-8222-222222222222";
  private static final String OTHER_CLIENT = "33333333-3333-4333-8333-333333333333";
  private static final ObjectMapper JSON = new ObjectMapper();

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    SessionService sessions = mock(SessionService.class);
    UserRepository.UserAccount host = account(1, "host");
    UserRepository.UserAccount guest = account(2, "guest");
    UserRepository.UserAccount other = account(3, "other");
    when(sessions.currentUser(any(HttpServletRequest.class))).thenAnswer(invocation -> {
      HttpServletRequest request = invocation.getArgument(0);
      return switch (String.valueOf(request.getHeader("X-Test-User"))) {
        case "host" -> host;
        case "guest" -> guest;
        case "other" -> other;
        default -> null;
      };
    });
    mvc = MockMvcBuilders.standaloneSetup(new GomokuApiController(new GomokuRoomService(), sessions))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void everyEndpointRequiresLoginBeforeCheckingTheClientIdentity() throws Exception {
    mvc.perform(post(BASE).header("X-Game-Client", "invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post(BASE + "/ABCDEF/join").header("X-Game-Client", "invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get(BASE + "/ABCDEF").header("X-Game-Client", "invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post(BASE + "/ABCDEF/moves").header("X-Game-Client", "invalid")
            .contentType(MediaType.APPLICATION_JSON).content("{\"row\":0,\"col\":0,\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post(BASE + "/ABCDEF/requests").header("X-Game-Client", "invalid")
            .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"restart\",\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post(BASE + "/ABCDEF/requests/respond").header("X-Game-Client", "invalid")
            .contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true,\"version\":0}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post(BASE + "/ABCDEF/leave").header("X-Game-Client", "invalid"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authenticatedRequestsRequireAValidClientUuid() throws Exception {
    mvc.perform(post(BASE).header("X-Test-User", "host"))
        .andExpect(status().isBadRequest());
    mvc.perform(auth(post(BASE), "host", "invalid"))
        .andExpect(status().isBadRequest());

    Room room = createRoom();
    mvc.perform(get(BASE + "/" + room.code()).header("X-Test-User", "host"))
        .andExpect(status().isBadRequest());
    mvc.perform(auth(post(BASE + "/" + room.code() + "/join"), "guest", "invalid"))
        .andExpect(status().isBadRequest());
    mvc.perform(auth(post(BASE + "/" + room.code() + "/leave"), "host", "invalid"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void roomMembershipIsBoundToTheAccountAndBrowserTab() throws Exception {
    Room room = createRoom();
    mvc.perform(auth(post(BASE), "other", OTHER_CLIENT))
        .andExpect(status().isOk());
    mvc.perform(auth(get(BASE + "/" + room.code()), "other", OTHER_CLIENT))
        .andExpect(status().isForbidden());
    mvc.perform(auth(get(BASE + "/" + room.code()), "host", GUEST_CLIENT))
        .andExpect(status().isForbidden());
    mvc.perform(auth(get(BASE + "/" + room.code()), "guest", HOST_CLIENT))
        .andExpect(status().isBadRequest());
    mvc.perform(auth(post(BASE + "/" + room.code() + "/moves"), "other", OTHER_CLIENT)
            .contentType(MediaType.APPLICATION_JSON).content(moveBody(7, 7, room.version())))
        .andExpect(status().isForbidden());

    mvc.perform(auth(post(BASE + "/" + room.code() + "/join"), "host", GUEST_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(room.code()))
        .andExpect(jsonPath("$.seat").value(2))
        .andExpect(jsonPath("$.phase").value("playing"));
  }

  @Test
  void moveBodiesMustIncludeCoordinatesAndVersion() throws Exception {
    Room room = joinRoom(createRoom());
    for (String body : new String[] {
        "{}",
        "{\"row\":7,\"version\":" + room.version() + "}",
        "{\"col\":7,\"version\":" + room.version() + "}",
        "{\"row\":7,\"col\":7}",
        moveBody(15, 0, room.version())
    }) {
      mvc.perform(auth(post(BASE + "/" + room.code() + "/moves"), "host", HOST_CLIENT)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }

    mvc.perform(auth(post(BASE + "/" + room.code() + "/moves"), "host", HOST_CLIENT)
            .contentType(MediaType.APPLICATION_JSON).content(moveBody(7, 7, room.version() - 1)))
        .andExpect(status().isConflict());
  }

  @Test
  void requestAndResponseBodiesPreserveMissingFieldsForValidation() throws Exception {
    Room room = joinRoom(createRoom());
    for (String body : new String[] {
        "{}",
        "{\"type\":\"restart\"}",
        "{\"version\":" + room.version() + "}",
        "{\"type\":\"pause\",\"version\":" + room.version() + "}"
    }) {
      mvc.perform(auth(post(BASE + "/" + room.code() + "/requests"), "host", HOST_CLIENT)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }

    MvcResult pending = mvc.perform(auth(post(BASE + "/" + room.code() + "/requests"), "host", HOST_CLIENT)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"restart\",\"version\":" + room.version() + "}"))
        .andExpect(status().isOk()).andReturn();
    Room requested = room(pending);
    for (String body : new String[] {
        "{}",
        "{\"accept\":true}",
        "{\"version\":" + requested.version() + "}"
    }) {
      mvc.perform(auth(post(BASE + "/" + room.code() + "/requests/respond"), "guest", GUEST_CLIENT)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }

    mvc.perform(auth(post(BASE + "/" + room.code() + "/requests/respond"), "guest", GUEST_CLIENT)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accept\":false,\"version\":" + requested.version() + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(requested.version() + 1))
        .andExpect(jsonPath("$.request").isEmpty());
  }

  @Test
  void waitingReadReturnsTheAuthoritativeMoveWhenTheRoomChanges() throws Exception {
    Room room = joinRoom(createRoom());
    MvcResult waiting = mvc.perform(auth(get(BASE + "/" + room.code()), "guest", GUEST_CLIENT)
            .param("since", Long.toString(room.version())).param("wait", "true"))
        .andExpect(request().asyncStarted()).andReturn();

    mvc.perform(auth(post(BASE + "/" + room.code() + "/moves"), "host", HOST_CLIENT)
            .contentType(MediaType.APPLICATION_JSON).content(moveBody(7, 7, room.version())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(room.version() + 1))
        .andExpect(jsonPath("$.game.board[7][7]").value(1));

    mvc.perform(asyncDispatch(waiting))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(room.code()))
        .andExpect(jsonPath("$.seat").value(2))
        .andExpect(jsonPath("$.version").value(room.version() + 1))
        .andExpect(jsonPath("$.game.board[7][7]").value(1))
        .andExpect(jsonPath("$.game.moves.length()").value(1))
        .andExpect(jsonPath("$.game.currentPlayer").value(2));
  }

  @Test
  void immediateReadDefaultsAndLeaveAreExposedThroughTheController() throws Exception {
    Room room = joinRoom(createRoom());
    MvcResult immediate = mvc.perform(auth(get(BASE + "/" + room.code()), "host", HOST_CLIENT))
        .andExpect(request().asyncStarted()).andReturn();
    mvc.perform(asyncDispatch(immediate))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(room.code()))
        .andExpect(jsonPath("$.version").value(room.version()));

    mvc.perform(auth(post(BASE + "/" + room.code() + "/leave"), "guest", GUEST_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase").value("closed"));
  }

  private Room createRoom() throws Exception {
    return room(mvc.perform(auth(post(BASE), "host", HOST_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seat").value(1))
        .andExpect(jsonPath("$.phase").value("waiting"))
        .andReturn());
  }

  private Room joinRoom(Room created) throws Exception {
    return room(mvc.perform(auth(post(BASE + "/" + created.code() + "/join"), "guest", GUEST_CLIENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seat").value(2))
        .andExpect(jsonPath("$.phase").value("playing"))
        .andReturn());
  }

  private static Room room(MvcResult result) throws Exception {
    JsonNode snapshot = JSON.readTree(result.getResponse().getContentAsString());
    return new Room(snapshot.get("code").asText(), snapshot.get("version").asLong());
  }

  private static MockHttpServletRequestBuilder auth(
      MockHttpServletRequestBuilder request, String username, String client) {
    return request.header("X-Test-User", username).header("X-Game-Client", client);
  }

  private static String moveBody(int row, int col, long version) {
    return "{\"row\":" + row + ",\"col\":" + col + ",\"version\":" + version + "}";
  }

  private static UserRepository.UserAccount account(long id, String username) {
    LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);
    return new UserRepository.UserAccount(id, username, "hash", "user", true, 1, now, now, now);
  }

  private record Room(String code, long version) {}
}
