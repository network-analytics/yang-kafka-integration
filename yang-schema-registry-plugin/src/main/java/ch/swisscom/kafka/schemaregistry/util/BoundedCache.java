//package ch.swisscom.kafka.schemaregistry.util;
//
//import com.google.common.cache.Cache;
//import com.google.common.cache.CacheBuilder;
//import com.google.common.cache.RemovalListener;
//import com.google.common.cache.RemovalNotification;
//import com.google.common.util.concurrent.UncheckedExecutionException;
//import java.util.concurrent.ConcurrentMap;
//import java.util.concurrent.ExecutionException;
//import java.util.concurrent.TimeUnit;
//import java.util.function.Supplier;
//
//public final class BoundedCache<K, V> {
//
//  private final Cache<K, V> cache;
//
//  public BoundedCache(int maxSize, long idleTimeoutMillis, Runnable onEviction) {
//    CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder().maximumSize(maxSize).softValues();
//    if (idleTimeoutMillis > 0) {
//      builder.expireAfterAccess(idleTimeoutMillis, TimeUnit.MILLISECONDS);
//    }
//    if (onEviction != null) {
//      RemovalListener<K, V> removalListener = (RemovalNotification<K, V> notification) -> onEviction.run();
//      this.cache = builder.removalListener(removalListener).build();
//    } else {
//      this.cache = builder.build();
//    }
//  }
//
//  public V get(K key) {
//    return cache.getIfPresent(key);
//  }
//
//  public void put(K key, V value) {
//    cache.put(key, value);
//  }
//
//  public V getOrCreate(K key, Supplier<V> supplier) {
//    try {
//      return cache.get(key, supplier::get);
//    } catch (ExecutionException | UncheckedExecutionException e) {
//      throw rethrow(key, e.getCause());
//    }
//  }
//
//  private RuntimeException rethrow(K key, Throwable cause) {
//    if (cause instanceof RuntimeException) {
//      return (RuntimeException) cause;
//    }
//    return new IllegalStateException("Failed to create cache entry for key " + key, cause);
//  }
//
//  public ConcurrentMap<K, V> asMap() {
//    return cache.asMap();
//  }
//
//  public int size() {
//    cache.cleanUp();
//    return (int) cache.size();
//  }
//}
