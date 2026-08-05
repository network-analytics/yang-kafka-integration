package ch.swisscom.kafka.schemaregistry.util;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class YangSchemaProviderMetrics {

  private static final Logger log = LoggerFactory.getLogger(YangSchemaProviderMetrics.class);

  private static final String DOMAIN = "ch.swisscom.kafka.schemaregistry.yang";

  private final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

  private final AtomicLong totalRequestCount = new AtomicLong();

  private final AtomicLong referenceCacheEvictionCount = new AtomicLong();
  private final AtomicLong parsedSchemaCacheEvictionCount = new AtomicLong();

  private final AtomicLong parsedSchemaCacheHitCount = new AtomicLong();
  private final AtomicLong parsedSchemaCacheMissCount = new AtomicLong();

  private final AtomicLong referenceCacheHitCount = new AtomicLong();
  private final AtomicLong referenceCacheMissCount = new AtomicLong();

  private final AtomicLong compatibleCount = new AtomicLong();
  private final AtomicLong incompatibleCount = new AtomicLong();

  private final ConcurrentHashMap<ParseErrorReason, AtomicLong> parseErrorCounts = new ConcurrentHashMap<>();

  public YangSchemaProviderMetrics(
      IntSupplier referenceCacheSizeSupplier,
      IntSupplier parsedSchemaCacheSizeSupplier) {

    ObjectName name = buildObjectName(DOMAIN + ":type=SchemaCache");
    if (name != null) {
      registerMBean(name,
              new GlobalMetrics(
                  referenceCacheSizeSupplier, parsedSchemaCacheSizeSupplier
              ),
              YangSchemaProviderMetricsMBean.class, false);
    }

    for (ParseErrorReason reason : ParseErrorReason.values()) {
      getOrRegisterParseErrorCounter(reason);
    }
  }

  public void recordRequest() {
    totalRequestCount.incrementAndGet();
  }

  public void recordReferenceCacheEviction() {
    referenceCacheEvictionCount.incrementAndGet();
  }

  public void recordParsedSchemaCacheEviction() {
    parsedSchemaCacheEvictionCount.incrementAndGet();
  }

  public void recordParsedSchemaCacheHit() {
    parsedSchemaCacheHitCount.incrementAndGet();
  }

  public void recordParsedSchemaCacheMiss() {
    parsedSchemaCacheMissCount.incrementAndGet();
  }

  public void recordReferenceCacheHit() {
    referenceCacheHitCount.incrementAndGet();
  }

  public void recordReferenceCacheMiss() {
    referenceCacheMissCount.incrementAndGet();
  }

  public void recordCompatibilityCheck(boolean isCompatible) {
    if (isCompatible) {
      compatibleCount.incrementAndGet();
    } else {
      incompatibleCount.incrementAndGet();
    }
  }

  public void recordParseError(ParseErrorReason reason) {
    getOrRegisterParseErrorCounter(reason).incrementAndGet();
  }

  private AtomicLong getOrRegisterParseErrorCounter(ParseErrorReason reason) {
    return parseErrorCounts.computeIfAbsent(reason, r -> {
      AtomicLong counter = new AtomicLong();
      ObjectName name = buildObjectName(DOMAIN + ":type=SchemaParseError,reason=" + r.label());
      if (name != null) {
        registerMBean(name, (ParseErrorMetricsMBean) counter::get, ParseErrorMetricsMBean.class, false);
      }
      return counter;
    });
  }

  private static ObjectName buildObjectName(String name) {
    try {
      return new ObjectName(name);
    } catch (MalformedObjectNameException e) {
      log.warn("Failed to build JMX object name '{}'", name, e);
      return null;
    }
  }

  private <T> void registerMBean(ObjectName name, T mbean, Class<T> mbeanInterface, boolean isMXBean) {
    try {
      if (mBeanServer.isRegistered(name)) {
        mBeanServer.unregisterMBean(name);
      }
      mBeanServer.registerMBean(new StandardMBean(mbean, mbeanInterface, isMXBean), name);
    } catch (Exception e) {
      log.warn("Failed to register JMX MBean '{}'", name, e);
    }
  }


  public interface YangSchemaProviderMetricsMBean {
    long getTotalRequestCount();

    long getReferenceCacheSize();

    long getReferenceCacheEvictionCount();

    long getParsedSchemaCacheSize();

    long getParsedSchemaCacheEvictionCount();

    long getParsedSchemaCacheHitCount();

    long getParsedSchemaCacheMissCount();

    long getReferenceCacheHitCount();

    long getReferenceCacheMissCount();

    long getCompatibleCount();

    long getIncompatibleCount();
  }

  public interface ParseErrorMetricsMBean {
    long getCount();
  }

  private class GlobalMetrics implements YangSchemaProviderMetricsMBean {
    private final IntSupplier referenceCacheSizeSupplier;
    private final IntSupplier parsedSchemaCacheSizeSupplier;

    GlobalMetrics(
        IntSupplier referenceCacheSizeSupplier,
        IntSupplier parsedSchemaCacheSizeSupplier
    ) {
      this.referenceCacheSizeSupplier = referenceCacheSizeSupplier;
      this.parsedSchemaCacheSizeSupplier = parsedSchemaCacheSizeSupplier;
    }

    @Override
    public long getTotalRequestCount() {
      return totalRequestCount.get();
    }

    @Override
    public long getReferenceCacheSize() {
      return referenceCacheSizeSupplier.getAsInt();
    }

    @Override
    public long getReferenceCacheEvictionCount() {
      return referenceCacheEvictionCount.get();
    }

    @Override
    public long getParsedSchemaCacheSize() {
      return parsedSchemaCacheSizeSupplier.getAsInt();
    }

    @Override
    public long getParsedSchemaCacheEvictionCount() {
      return parsedSchemaCacheEvictionCount.get();
    }

    @Override
    public long getParsedSchemaCacheHitCount() {
      return parsedSchemaCacheHitCount.get();
    }

    @Override
    public long getParsedSchemaCacheMissCount() {
      return parsedSchemaCacheMissCount.get();
    }

    @Override
    public long getReferenceCacheHitCount() {
      return referenceCacheHitCount.get();
    }

    @Override
    public long getReferenceCacheMissCount() {
      return referenceCacheMissCount.get();
    }

    @Override
    public long getCompatibleCount() {
      return compatibleCount.get();
    }

    @Override
    public long getIncompatibleCount() {
      return incompatibleCount.get();
    }
  }
}
