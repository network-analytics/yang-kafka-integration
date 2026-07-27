package ch.swisscom.kafka.schemaregistry.util;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class BoundedCache<K, V> {

  private final Cache<K, V> cache;

  public BoundedCache(int maxSize, long idleTimeoutMillis, Runnable onEviction) {
    CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder().maximumSize(maxSize);
    if (idleTimeoutMillis > 0) {
      builder.expireAfterAccess(idleTimeoutMillis, TimeUnit.MILLISECONDS);
    }
    if (onEviction != null) {
      RemovalListener<K, V> removalListener = notification -> onEviction.run();
      this.cache = builder.removalListener(removalListener).build();
    } else {
      this.cache = builder.build();
    }
  }

  public V get(K key) {
    return cache.getIfPresent(key);
  }

  public void put(K key, V value) {
    cache.put(key, value);
  }

  public V getOrCreate(K key, Supplier<V> supplier) {
    try {
      return cache.get(key, supplier::get);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Failed to create cache entry for key " + key, e);
    }
  }

  public ConcurrentMap<K, V> asMap() {
    return cache.asMap();
  }

  public int size() {
    cache.cleanUp();
    return (int) cache.size();
  }
}
