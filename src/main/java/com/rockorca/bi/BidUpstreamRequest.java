package com.rockorca.bi;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.cert.CertificateException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLException;

/** Bounded retries for the upstream read-only report requests. */
final class BidUpstreamRequest {
  static final int MAX_ATTEMPTS=3;
  static final long BUDGET_MILLIS=60_000,REQUEST_MILLIS=40_000,MAX_RETRY_AFTER_MILLIS=5_000;
  private static final Set<Integer> RETRY_STATUSES=Set.of(429,500,502,503,504);
  enum Kind { HTTP,TIMEOUT,CONNECT,IO,TLS,BUDGET }
  @FunctionalInterface interface Waiter { void sleep(long millis)throws InterruptedException; }

  static final class Failure extends IOException {
    private final String platform;
    private final Kind kind;
    private final int status,attempts;
    Failure(String platform,Kind kind,int status,int attempts){
      super(platform+"接口"+switch(kind){
        case HTTP -> " HTTP "+status;
        case TIMEOUT -> "请求超时";
        case CONNECT -> "连接失败";
        case IO -> "网络读写失败";
        case TLS -> " TLS 证书或加密连接校验失败";
        case BUDGET -> "请求重试次数或时间预算已用尽";
      }+"（已尝试 "+attempts+" 次）");
      this.platform=platform;this.kind=kind;this.status=status;this.attempts=attempts;
    }
    String platform(){return platform;}
    Kind kind(){return kind;}
    int status(){return status;}
    int attempts(){return attempts;}
    boolean retryable(){return kind!=Kind.TLS&&(kind!=Kind.HTTP||RETRY_STATUSES.contains(status));}
  }

  static final class Rejection extends IllegalArgumentException {
    private final String platform,code;
    Rejection(String platform,String code,String safeMessage){super(safeMessage);this.platform=platform;this.code=code;}
    String platform(){return platform;}
    String code(){return code;}
  }

  static final class Budget {
    private final long started;
    private int attempts;
    private Budget(long started){this.started=started;}
  }

  private final HttpClient client;
  private final LongSupplier ticker;
  private final Clock clock;
  private final Waiter waiter;
  BidUpstreamRequest(HttpClient client){this(client,System::nanoTime,Clock.systemUTC(),Thread::sleep);}
  BidUpstreamRequest(HttpClient client,LongSupplier ticker,Clock clock,Waiter waiter){
    this.client=client;this.ticker=ticker;this.clock=clock;this.waiter=waiter;
  }
  Budget begin(){return new Budget(ticker.getAsLong());}
  HttpResponse<String> send(String platform,Function<Duration,HttpRequest> request)throws IOException,InterruptedException{
    return send(platform,request,begin());
  }
  HttpResponse<String> send(String platform,Function<Duration,HttpRequest> request,Budget budget)throws IOException,InterruptedException{
    while(true){
      if(Thread.currentThread().isInterrupted())throw interrupted();
      long remaining=remaining(budget);
      if(budget.attempts>=MAX_ATTEMPTS||remaining<=0)throw new Failure(platform,Kind.BUDGET,0,budget.attempts);
      budget.attempts++;
      HttpResponse<String> response;
      try{
        response=client.send(request.apply(Duration.ofMillis(Math.min(REQUEST_MILLIS,remaining))),HttpResponse.BodyHandlers.ofString());
      }catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}
      catch(IOException error){
        if(Thread.currentThread().isInterrupted())throw interrupted();
        Failure failure=new Failure(platform,kind(error),0,budget.attempts);
        if(!failure.retryable())throw failure;
        retry(budget,backoff(budget.attempts),failure);continue;
      }
      int status=response.statusCode();
      if(status==200)return response;
      Failure failure=new Failure(platform,Kind.HTTP,status,budget.attempts);
      if(!failure.retryable())throw failure;
      retry(budget,retryDelay(response,budget.attempts),failure);
    }
  }
  private long remaining(Budget budget){return BUDGET_MILLIS-Math.max(0,TimeUnit.NANOSECONDS.toMillis(ticker.getAsLong()-budget.started));}
  private void retry(Budget budget,long delay,Failure failure)throws Failure,InterruptedException{
    // A server-requested delay cannot be shortened without violating its rate limit.
    if(budget.attempts>=MAX_ATTEMPTS||delay>MAX_RETRY_AFTER_MILLIS||remaining(budget)<=delay)throw failure;
    try{waiter.sleep(delay);}catch(InterruptedException error){Thread.currentThread().interrupt();throw error;}
  }
  private static long backoff(int attempts){return 250L*attempts;}
  private long retryDelay(HttpResponse<?> response,int attempts){
    String value=response.headers().firstValue("Retry-After").orElse("").trim();
    if(!value.isEmpty())try{
      long delay=value.matches("[0-9]+")?Math.multiplyExact(Long.parseLong(value),1000L)
          :Duration.between(clock.instant(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
      return Math.max(0,delay);
    }catch(ArithmeticException|NumberFormatException ignored){return Long.MAX_VALUE;}
    catch(RuntimeException ignored){}
    return backoff(attempts);
  }
  private static Kind kind(IOException error){
    for(Throwable cause=error;cause!=null;cause=cause.getCause()){
      if(cause instanceof SSLException||cause instanceof CertificateException)return Kind.TLS;
      if(cause==cause.getCause())break;
    }
    if(error instanceof HttpConnectTimeoutException)return Kind.CONNECT;
    if(error instanceof HttpTimeoutException)return Kind.TIMEOUT;
    if(error instanceof ConnectException||error instanceof UnknownHostException)return Kind.CONNECT;
    return Kind.IO;
  }
  private static InterruptedException interrupted(){Thread.currentThread().interrupt();return new InterruptedException("上游查询已中断");}
}
