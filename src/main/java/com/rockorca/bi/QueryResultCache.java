package com.rockorca.bi;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded completed results; identical requests share work, unrelated queries never hold a global lock. */
final class QueryResultCache<K,V> {
  private record Entry<V>(long expires,V value) {}
  private final Map<K,Entry<V>> completed=new LinkedHashMap<>(16,.75f,true);
  private final Map<K,CompletableFuture<V>> pending=new HashMap<>();
  private final int limit;private final long ttlNanos;
  QueryResultCache(int limit,long ttlMillis){this.limit=limit;this.ttlNanos=TimeUnit.MILLISECONDS.toNanos(ttlMillis);}
  V get(K key,Supplier<V> loader){
    CompletableFuture<V> future;boolean load=false;
    synchronized(this){
      var entry=completed.get(key);if(entry!=null&&System.nanoTime()<entry.expires())return entry.value();
      completed.remove(key);future=pending.get(key);
      if(future==null){future=new CompletableFuture<>();pending.put(key,future);load=true;}
    }
    if(load){
      try{
        V value=loader.get();
        synchronized(this){
          if(pending.remove(key,future)){
            completed.put(key,new Entry<>(System.nanoTime()+ttlNanos,value));
            while(completed.size()>limit)completed.remove(completed.keySet().iterator().next());
          }
        }
        future.complete(value);
      }catch(Throwable error){synchronized(this){pending.remove(key,future);}future.completeExceptionally(error);}
    }
    try{return future.join();}catch(CompletionException error){
      if(error.getCause() instanceof RuntimeException cause)throw cause;
      if(error.getCause() instanceof Error cause)throw cause;
      throw error;
    }
  }
  synchronized void clear(){completed.clear();pending.clear();}
}
