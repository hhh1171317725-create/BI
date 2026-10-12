package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GarminApiControllerTest {
  final SessionService sessions=mock(SessionService.class);final UserService users=mock(UserService.class);
  final GarminSyncService service=mock(GarminSyncService.class);final GarminApiController controller=new GarminApiController(sessions,users,service);
  final MockHttpServletRequest request=new MockHttpServletRequest();
  @BeforeEach void setup(){when(sessions.currentUser(request)).thenReturn(new UserRepository.UserAccount(7,"owner","hash","user",true,1,null,null,null));when(users.canUseTool(any(),eq("garmin"))).thenReturn(true);}
  @Test void healthEndpointsUseOnlyTheAuthenticatedOwner(){
    controller.status(request);controller.data(request,"2026-10-01","2026-10-02");controller.sync(request,Map.of("expectedUserId","7","userId","8"));
    verify(service).status(7);verify(service).data(7,"2026-10-01","2026-10-02");verify(service).sync(eq(7L),anyMap());verify(service,never()).status(8);
  }
  @Test void missingInactiveAndDisabledActorsCannotReadOrMutateHealthData(){
    when(sessions.currentUser(request)).thenReturn(null);assertEquals(401,assertThrows(ResponseStatusException.class,()->controller.status(request)).getStatusCode().value());
    when(sessions.currentUser(request)).thenReturn(new UserRepository.UserAccount(7,"owner","hash","user",false,1,null,null,null));assertThrows(ResponseStatusException.class,()->controller.data(request,"",""));
    when(sessions.currentUser(request)).thenReturn(new UserRepository.UserAccount(7,"owner","hash","user",true,1,null,null,null));when(users.canUseTool(any(),eq("garmin"))).thenReturn(false);
    assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.disconnect(request,Map.of("expectedUserId","7"))).getStatusCode().value());verifyNoInteractions(service);
  }
  @Test void accountSwitchReturnsAStableCodeAndNeverStartsTheOldOwnersJob(){
    assertThrows(GarminApiController.AccountChanged.class,()->controller.connect(request,Map.of("expectedUserId","8")));
    assertEquals(409,controller.changedAccount().getStatusCode().value());assertEquals("ACCOUNT_CHANGED",controller.changedAccount().getBody().get("code"));verifyNoInteractions(service);
  }
  @Test void crossOriginAndCrossSiteMutationsAreRejectedWithoutEchoingHeaders(){
    request.addHeader("Host","bi.example.com");request.addHeader("Origin","https://evil.example.com/private-token");
    var error=assertThrows(ResponseStatusException.class,()->controller.sync(request,Map.of("expectedUserId","7")));assertEquals(403,error.getStatusCode().value());assertFalse(error.getReason().contains("private-token"));
    request.removeHeader("Origin");request.addHeader("Sec-Fetch-Site","same-site");assertThrows(ResponseStatusException.class,()->controller.disconnect(request,Map.of("expectedUserId","7")));verifyNoInteractions(service);
  }
  @Test void sameOriginBehindHttpsProxyWorksAndResponsesDisableHealthCaching(){
    request.addHeader("Host","bi.example.com");request.addHeader("X-Forwarded-Proto","https");request.addHeader("Origin","https://bi.example.com");request.addHeader("Sec-Fetch-Site","same-origin");
    controller.settings(request,Map.of("expectedUserId","7"));verify(service).settings(eq(7L),anyMap());
    var response=new MockHttpServletResponse();controller.noCache(response);assertEquals("no-store, private",response.getHeader("Cache-Control"));
  }
  @Test void actualMvcHealthAndAccountSwitchResponsesHavePrivateNoStoreHeaders()throws Exception{
    var actor=new UserRepository.UserAccount(7,"owner","hash","user",true,1,null,null,null);
    when(sessions.currentUser(any())).thenReturn(actor);when(service.status(7)).thenReturn(Map.of("userId","7","connected",false));
    var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(get("/api/garmin/status")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"));
    mvc.perform(post("/api/garmin/disconnect").contentType("application/json").content("{\"expectedUserId\":\"8\"}"))
        .andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store, private")).andExpect(jsonPath("$.code").value("ACCOUNT_CHANGED"));
  }
}
