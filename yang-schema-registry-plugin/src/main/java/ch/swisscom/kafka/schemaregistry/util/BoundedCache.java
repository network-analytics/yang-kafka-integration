package ch.swisscom.kafka.schemaregistry.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BoundedCache<K, V> {

  private static final int DEFAULT_INITIAL_CAPACITY = 16;
  private static final float DEFAULT_LOAD_FACTOR = 0.75f;
  private static final boolean ACCESS_ORDER = true; // LRU (Least Recently Used) order

  private final int maxSize;
  private final Runnable onEviction;
  private final Map<K, V> map;

  public BoundedCache(int maxSize) {
    this(maxSize, null);
  }

  public BoundedCache(int maxSize, Runnable onEviction) {
    this.maxSize = maxSize;
    this.onEviction = onEviction;
    this.map = Collections.synchronizedMap(
        new LinkedHashMap<>(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, ACCESS_ORDER) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            boolean shouldRemove = size() > BoundedCache.this.maxSize;
            if (shouldRemove && BoundedCache.this.onEviction != null) {
              BoundedCache.this.onEviction.run();
            }
            return shouldRemove;
          }
        });
  }

  public V get(K key) {
    return map.get(key);
  }

  public void put(K key, V value) {
    map.put(key, value);
  }

  public int size() {
    return map.size();
  }
}

