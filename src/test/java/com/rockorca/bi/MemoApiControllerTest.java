package com.rockorca.bi;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MemoApiControllerTest {
  @Test void sessionIsRequiredForReadAndWrite() throws Exception {
    var service = mock(MemoService.class); var sessions = mock(SessionService.class);
    var mvc = MockMvcBuilders.standaloneSetup(new MemoApiController(service, sessions)).setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(get("/api/memos")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/memos").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"test\"}")).andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/memos/3").param("version", "1")).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test void clientCannotChooseAnotherOwner() throws Exception {
    var service = mock(MemoService.class); var sessions = mock(SessionService.class);
    when(sessions.currentUser(any())).thenReturn(new UserRepository.UserAccount(42,"user","hash","member",true,1,null,null,null));
    when(service.save(eq(42L),isNull(),anyMap())).thenReturn(Map.of("id",1));
    var mvc = MockMvcBuilders.standaloneSetup(new MemoApiController(service,sessions)).setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(post("/api/memos").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"test\",\"user_id\":99}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
    verify(service).save(eq(42L),isNull(),anyMap());
    mvc.perform(get("/api/memos/5").param("user_id","99")).andExpect(status().isOk());
    verify(service).get(42,5);
  }
}
