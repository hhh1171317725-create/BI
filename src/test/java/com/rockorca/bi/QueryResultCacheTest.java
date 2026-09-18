package com.rockorca.bi;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryResultCacheTest {
  @Test void sharesIdenticalWorkWithoutBlockingUnrelatedQueries()throws Exception{
    var cache=new QueryResultCache<String,Integer>(4,60_000);var started=new CountDownLatch(1);var release=new CountDownLatch(1);var reads=new AtomicInteger();
    try(var pool=Executors.newFixedThreadPool(10)){
      var first=pool.submit(()->cache.get("A",()->{reads.incrementAndGet();started.countDown();await(release);return 7;}));
      assertTrue(started.await(2,TimeUnit.SECONDS));
      var jobs=new ArrayList<Future<Integer>>();for(int i=0;i<8;i++)jobs.add(pool.submit(()->cache.get("A",()->{reads.incrementAndGet();return 8;})));
      try{assertEquals(9,pool.submit(()->cache.get("B",()->9)).get(2,TimeUnit.SECONDS));}finally{release.countDown();}
      assertEquals(7,first.get(2,TimeUnit.SECONDS));for(var job:jobs)assertEquals(7,job.get(2,TimeUnit.SECONDS));assertEquals(1,reads.get());
    }
  }
  @Test void clearDuringCalculationCannotRestoreOldResult()throws Exception{
    var cache=new QueryResultCache<String,Integer>(2,60_000);var started=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var pool=Executors.newSingleThreadExecutor()){
      var old=pool.submit(()->cache.get("A",()->{started.countDown();await(release);return 1;}));
      assertTrue(started.await(2,TimeUnit.SECONDS));cache.clear();
      try{assertEquals(2,cache.get("A",()->2));}finally{release.countDown();}
      assertEquals(1,old.get(2,TimeUnit.SECONDS));assertEquals(2,cache.get("A",()->3));
    }
  }
  @Test void failedQueriesRetryAndCompletedCacheIsBounded(){
    var cache=new QueryResultCache<String,Integer>(2,60_000);
    assertThrows(IllegalStateException.class,()->cache.get("A",()->{throw new IllegalStateException("failed");}));
    assertEquals(1,cache.get("A",()->1));cache.get("B",()->2);cache.get("C",()->3);assertEquals(4,cache.get("A",()->4));
    var expired=new QueryResultCache<String,Integer>(2,0);expired.get("A",()->1);assertEquals(2,expired.get("A",()->2));
  }
  static void await(CountDownLatch latch){try{if(!latch.await(3,TimeUnit.SECONDS))throw new AssertionError("timed out");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new RuntimeException(error);}}
}
