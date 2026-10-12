package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GarminExternalApiControllerTest {
  @TempDir Path dir;
  final GarminSyncServiceTest fixture=new GarminSyncServiceTest();
  final SessionService sessions=mock(SessionService.class);
  MockMvc mvc;String bearer;
  @BeforeEach void setup()throws Exception{
    fixture.dir=dir;fixture.setup();fixture.login(7,"synthetic@example.invalid");fixture.syncDay(7,17);
    bearer="Bearer "+fixture.service.createReadKey(7,Map.of("days",30)).get("apiKey");
    mvc=MockMvcBuilders.standaloneSetup(new GarminExternalApiController(fixture.service,fixture.mapper),new GarminApiController(sessions,fixture.users,fixture.service))
        .addInterceptors(new AuthInterceptor(sessions,fixture.mapper),new ToolPermissionInterceptor(sessions,fixture.users,fixture.mapper)).build();
  }
  @AfterEach void close(){fixture.close();}
  @Test void noCookieOrQueryParameterCanReplaceTheBearerCredential()throws Exception{
    when(sessions.authenticated(any())).thenReturn(true);
    for(String path:List.of("/api/garmin-external/status","/api/garmin-external/data?apiKey="+bearer.substring(7)))
      mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store, private")).andExpect(header().string("WWW-Authenticate","Bearer"));
    mvc.perform(get("/api/garmin-external/status").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
  }
  @Test void validKeyReadsOnlyOwnCachedSummariesWithoutStartingWorkers()throws Exception{
    int starts=fixture.runner.starts.get();
    mvc.perform(get("/api/garmin-external/data").header("Authorization",bearer).param("startDate",fixture.date).param("endDate",fixture.date).param("userId","8"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.days[0].summary.steps").value(17)).andExpect(jsonPath("$.days[0].summary.hrvMs").isEmpty())
        .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.days[0].activities").doesNotExist()).andExpect(header().string("Cache-Control","no-store, private"));
    mvc.perform(get("/api/garmin-external/status").header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.source").value("website_cache"));
    assertEquals(starts,fixture.runner.starts.get());
    mvc.perform(post("/api/garmin/access-key/create").header("Authorization",bearer).contentType("application/json").content("{\"expectedUserId\":\"7\",\"days\":30}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/garmin-external/status").header("Authorization",bearer)).andExpect(status().isMethodNotAllowed());
  }
  @Test void revokedAndPermissionDisabledKeysStopWorking()throws Exception{
    when(fixture.users.canUseTool(any(),eq("garmin"))).thenReturn(false);
    mvc.perform(get("/api/garmin-external/status").header("Authorization",bearer)).andExpect(status().isForbidden());
    when(fixture.users.canUseTool(any(),eq("garmin"))).thenReturn(true);fixture.service.revokeReadKey(7);
    mvc.perform(get("/api/garmin-external/status").header("Authorization",bearer)).andExpect(status().isUnauthorized());
  }
  @Test void invalidDateRangeCategoryAndPaginationReturnSafeErrors()throws Exception{
    for(String date:List.of("../../state","2026-02-31","1999-12-31"))
      mvc.perform(get("/api/garmin-external/day").header("Authorization",bearer).param("date",date)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/garmin-external/data").header("Authorization",bearer).param("startDate","2026-01-01").param("endDate","2026-02-01")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/garmin-external/day").header("Authorization",bearer).param("date",fixture.date).param("category","secret")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/garmin-external/activities").header("Authorization",bearer).param("date",fixture.date).param("limit","999")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/garmin-external/activities").header("Authorization",bearer).param("date",fixture.date).param("offset","not-a-number")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/garmin-external/day").header("Authorization",bearer)).andExpect(status().isBadRequest());
  }
  void detailFixture()throws Exception{
    var payload=new LinkedHashMap<String,Object>(GarminSyncServiceTest.payload(20));var activities=new ArrayList<Map<String,Object>>();
    for(int i=1;i<=3;i++)activities.add(Map.of("id",Integer.toString(i),"name","Synthetic Run","type","running","durationSeconds",100,
        "details",Map.of("samples",GarminSyncServiceTest.record(java.util.stream.IntStream.range(0,100).boxed().toList()))));
    payload.put("activities",activities);payload.put("details",Map.of("sleep",GarminSyncServiceTest.record(Map.of("stages",java.util.stream.IntStream.range(0,100).boxed().toList())),"spo2",GarminSyncServiceTest.record(Map.of("average",98))));
    fixture.detailedSync(7,payload,Map.of("startDate",fixture.date,"endDate",fixture.date,"includeDetails",true));
  }
  @Test void categoryAndSampleTruncationIsExplicitAndActivitiesArePagedAndOwnerBound()throws Exception{
    detailFixture();
    mvc.perform(get("/api/garmin-external/day").header("Authorization",bearer).param("date",fixture.date).param("category","sleep"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.details.sleep.value.stages.length()").value(30)).andExpect(jsonPath("$.responseLimited").value(true)).andExpect(jsonPath("$.details.spo2").doesNotExist());
    mvc.perform(get("/api/garmin-external/activities").header("Authorization",bearer).param("date",fixture.date).param("limit","2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.nextOffset").value(2)).andExpect(jsonPath("$.activities.length()").value(2));
    mvc.perform(get("/api/garmin-external/activities").header("Authorization",bearer).param("date",fixture.date).param("offset","2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.nextOffset").isEmpty()).andExpect(jsonPath("$.activities[0].id").value("3"));
    mvc.perform(get("/api/garmin-external/activity").header("Authorization",bearer).param("date",fixture.date).param("activityId","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.details.samples").doesNotExist()).andExpect(jsonPath("$.omittedCategories[0]").value("samples"));
    mvc.perform(get("/api/garmin-external/activity").header("Authorization",bearer).param("date",fixture.date).param("activityId","1").param("category","samples"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.details.samples.value.length()").value(30)).andExpect(jsonPath("$.responseLimited").value(true));
    mvc.perform(get("/api/garmin-external/activity").header("Authorization",bearer).param("date",fixture.date).param("activityId","999"))
        .andExpect(status().isNotFound());
  }
  @Test void rateLimitedResponseProvidesRetryAdvice()throws Exception{
    for(int i=0;i<60;i++)fixture.service.withReadKey(bearer,fixture.service::externalStatus);
    mvc.perform(get("/api/garmin-external/status").header("Authorization",bearer)).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","60"));
  }
  @Test void unusuallyWideProviderDetailsCannotExceedGptResponseLimit()throws Exception{
    var fields=new LinkedHashMap<String,Object>();for(int i=0;i<500;i++)fields.put("field"+i,"x".repeat(200));
    var payload=new LinkedHashMap<String,Object>(GarminSyncServiceTest.payload(20));payload.put("details",Map.of("daily",GarminSyncServiceTest.record(fields)));
    fixture.detailedSync(7,payload,Map.of("startDate",fixture.date,"endDate",fixture.date,"includeDetails",true));
    var response=mvc.perform(get("/api/garmin-external/day").header("Authorization",bearer).param("date",fixture.date))
        .andExpect(status().isOk()).andExpect(jsonPath("$.responseLimited").value(true)).andExpect(jsonPath("$.summary.steps").value(20)).andReturn().getResponse();
    assertTrue(response.getContentAsString().length()<80_000);
  }
  @Test void openApiDescribesTheExactReadPathsAndBearerSecurity()throws Exception{
    var spec=fixture.mapper.readValue(Path.of("frontend/garmin-openapi.json").toFile(),Map.class);var paths=(Map<?,?>)spec.get("paths");
    assertEquals(Set.of("/api/garmin-external/status","/api/garmin-external/data","/api/garmin-external/day","/api/garmin-external/activities","/api/garmin-external/activity"),paths.keySet());
    var schemes=(Map<?,?>)((Map<?,?>)spec.get("components")).get("securitySchemes");assertEquals("bearer",((Map<?,?>)schemes.get("GarminReadKey")).get("scheme"));
    for(Object path:paths.values()){assertEquals(Set.of("get"),((Map<?,?>)path).keySet());var op=(Map<?,?>)((Map<?,?>)path).get("get");assertEquals(false,op.get("x-openai-isConsequential"));assertTrue(op.get("description").toString().length()<=300);}
  }
}
