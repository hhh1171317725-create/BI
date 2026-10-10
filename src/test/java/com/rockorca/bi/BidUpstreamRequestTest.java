package com.rockorca.bi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BidUpstreamRequestTest {
  private static final Instant NOW=Instant.parse("2026-10-10T00:00:00Z");
  private static final class Harness {
    final HttpClient client=mock(HttpClient.class);
    final AtomicLong millis=new AtomicLong();
    final List<Long> waits=new ArrayList<>();
    final BidUpstreamRequest requests=new BidUpstreamRequest(client,()->millis.get()*1_000_000,
        Clock.fixed(NOW,ZoneOffset.UTC),delay->{waits.add(delay);millis.addAndGet(delay);});
    final AtomicLong ids=new AtomicLong();
    HttpRequest request(Duration timeout){return HttpRequest.newBuilder(URI.create("https://upstream.example/report"))
        .timeout(timeout).header("ff-request-id","request-"+ids.incrementAndGet())
        .POST(HttpRequest.BodyPublishers.ofString("same query body")).build();}
  }
  private static HttpResponse<String> response(int status,String retryAfter){
    return response(status,"",retryAfter);
  }
  static HttpResponse<String> response(int status,String json,String retryAfter){
    HttpHeaders headers=HttpHeaders.of(retryAfter==null?Map.of():Map.of("Retry-After",List.of(retryAfter)),(name,value)->true);
    return new HttpResponse<>(){
      public int statusCode(){return status;}
      public HttpRequest request(){return null;}
      public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();}
      public HttpHeaders headers(){return headers;}
      public String body(){return json;}
      public Optional<SSLSession> sslSession(){return Optional.empty();}
      public URI uri(){return URI.create("https://upstream.example/report");}
      public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
    };
  }
  static String body(HttpRequest request){
    var bytes=new ByteArrayOutputStream();var result=new CompletableFuture<String>();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
      public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
      public void onNext(ByteBuffer buffer){byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}
      public void onError(Throwable error){result.completeExceptionally(error);}
      public void onComplete(){result.complete(bytes.toString(StandardCharsets.UTF_8));}
    });
    return result.join();
  }

  @Test void transientHttpAndIoRetryWithFreshRequestIdsAndTheSameQueryBody()throws Exception{
    var h=new Harness();HttpResponse<String> success=response(200,null);
    when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
        .thenReturn(response(503,null)).thenThrow(new IOException("private transport detail")).thenReturn(success);
    assertSame(success,h.requests.send("字节",h::request));assertEquals(List.of(250L,500L),h.waits);
    var sent=ArgumentCaptor.forClass(HttpRequest.class);verify(h.client,times(3)).send(sent.capture(),any(HttpResponse.BodyHandler.class));
    assertEquals(List.of("request-1","request-2","request-3"),sent.getAllValues().stream().map(r->r.headers().firstValue("ff-request-id").orElseThrow()).toList());
    assertTrue(sent.getAllValues().stream().allMatch(r->"same query body".equals(body(r))));
    assertTrue(sent.getAllValues().stream().allMatch(r->r.timeout().orElseThrow().toMillis()<=40_000));
  }

  @Test void retryableHttpStatusesStopAfterThreeAttemptsWithoutPersistingBodies()throws Exception{
    for(int status:List.of(429,500,502,503,504)){
      var h=new Harness();when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(status,null));
      var failure=assertThrows(BidUpstreamRequest.Failure.class,()->h.requests.send("广点通",h::request));
      assertEquals(status,failure.status());assertEquals(3,failure.attempts());assertTrue(failure.retryable());
      assertEquals(BidUpstreamRequest.Kind.HTTP,failure.kind());assertNull(failure.getCause());
      verify(h.client,times(3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
  }

  @Test void authenticationAndOtherPermanentHttpFailuresDoNotRetry()throws Exception{
    for(int status:List.of(401,403,404)){
      var h=new Harness();when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(status,null));
      var failure=assertThrows(BidUpstreamRequest.Failure.class,()->h.requests.send("字节",h::request));
      assertFalse(failure.retryable());assertEquals(1,failure.attempts());assertTrue(h.waits.isEmpty());
      verify(h.client).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
  }

  @Test void retryAfterSecondsAndHttpDatesAreRespectedWithoutRealSleeping()throws Exception{
    for(String retryAfter:List.of("3","Sat, 10 Oct 2026 00:00:03 GMT")){
      var h=new Harness();when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
          .thenReturn(response(429,retryAfter),response(200,null));
      h.requests.send("字节",h::request);assertEquals(List.of(3_000L),h.waits);
    }
  }

  @Test void oversizedRetryAfterNeverTriggersAnEarlyRequest()throws Exception{
    for(String retryAfter:List.of("120","999999999999999999999999999999999999","Sat, 10 Oct 2026 00:02:00 GMT")){
      var h=new Harness();when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(429,retryAfter));
      var failure=assertThrows(BidUpstreamRequest.Failure.class,()->h.requests.send("字节",h::request));
      assertEquals(429,failure.status());assertEquals(1,failure.attempts());assertTrue(h.waits.isEmpty());
      verify(h.client).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
  }

  @Test void malformedRetryAfterUsesShortBackoff()throws Exception{
    var h=new Harness();when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
        .thenReturn(response(503,"invalid date"),response(200,null));
    h.requests.send("字节",h::request);assertEquals(List.of(250L),h.waits);
  }

  @Test void theSixtySecondBudgetLimitsLaterRequestTimeoutsAndStopsAnotherRetry()throws Exception{
    var h=new Harness();when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(invocation->{
      h.millis.addAndGet(h.ids.get()==1?40_000:19_750);throw new HttpTimeoutException("private timeout detail");
    });
    var failure=assertThrows(BidUpstreamRequest.Failure.class,()->h.requests.send("字节",h::request));
    assertEquals(BidUpstreamRequest.Kind.TIMEOUT,failure.kind());assertEquals(2,failure.attempts());
    var sent=ArgumentCaptor.forClass(HttpRequest.class);verify(h.client,times(2)).send(sent.capture(),any(HttpResponse.BodyHandler.class));
    assertEquals(List.of(40_000L,19_750L),sent.getAllValues().stream().map(r->r.timeout().orElseThrow().toMillis()).toList());
    assertEquals(List.of(250L),h.waits);assertFalse(failure.getMessage().contains("private"));
  }

  @Test void tlsFailuresIncludingWrappedCertificateFailuresDoNotRetry()throws Exception{
    for(IOException error:List.of(new SSLHandshakeException("private certificate detail"),new IOException("private outer detail",new java.security.cert.CertificateException("private inner detail")))){
      var h=new Harness();when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(error);
      var failure=assertThrows(BidUpstreamRequest.Failure.class,()->h.requests.send("广点通",h::request));
      assertEquals(BidUpstreamRequest.Kind.TLS,failure.kind());assertFalse(failure.retryable());assertNull(failure.getCause());
      assertFalse(failure.getMessage().contains("private"));assertTrue(h.waits.isEmpty());
      verify(h.client).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
  }

  @Test void connectionFailuresHaveSafeTypedDetails()throws Exception{
    var h=new Harness();when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new ConnectException("private address"));
    var failure=assertThrows(BidUpstreamRequest.Failure.class,()->h.requests.send("广点通",h::request));
    assertEquals("广点通",failure.platform());assertEquals(BidUpstreamRequest.Kind.CONNECT,failure.kind());
    assertEquals(0,failure.status());assertEquals(3,failure.attempts());assertFalse(failure.getMessage().contains("private"));
  }

  @Test void interruptionDuringTheRequestOrBackoffIsRestoredAndNeverRetried()throws Exception{
    for(boolean duringWait:List.of(false,true))try{
      var h=new Harness();BidUpstreamRequest requests=h.requests;
      if(duringWait){
        when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(503,null));
        requests=new BidUpstreamRequest(h.client,()->0L,Clock.fixed(NOW,ZoneOffset.UTC),delay->{throw new InterruptedException("interrupted wait");});
      }else when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException("interrupted request"));
      BidUpstreamRequest subject=requests;assertThrows(InterruptedException.class,()->subject.send("字节",h::request));
      assertTrue(Thread.currentThread().isInterrupted());verify(h.client).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }finally{Thread.interrupted();}
  }

  @Test void anAlreadyInterruptedActionSendsNoRequest()throws Exception{
    var h=new Harness();try{
      Thread.currentThread().interrupt();assertThrows(InterruptedException.class,()->h.requests.send("字节",h::request));
      assertTrue(Thread.currentThread().isInterrupted());verifyNoInteractions(h.client);
    }finally{Thread.interrupted();}
  }

  @Test void compatibilityRequestsReuseTheSameAttemptAndTimeBudget()throws Exception{
    var h=new Harness();when(h.client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,null));
    var budget=h.requests.begin();for(int i=0;i<3;i++)h.requests.send("字节",h::request,budget);
    var failure=assertThrows(BidUpstreamRequest.Failure.class,()->h.requests.send("字节",h::request,budget));
    assertEquals(BidUpstreamRequest.Kind.BUDGET,failure.kind());assertEquals(3,failure.attempts());
    verify(h.client,times(3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }
}
