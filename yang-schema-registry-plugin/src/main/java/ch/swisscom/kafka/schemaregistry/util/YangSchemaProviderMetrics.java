package ch.swisscom.kafka.schemaregistry.util;

import java.lang.management.ManagementFactory;
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

  private final AtomicLong referenceModuleCacheEvictionCount = new AtomicLong();

  private final AtomicLong parsedSchemaCacheEvictionCount = new AtomicLong();

  private final AtomicLong parsedSchemaCacheHitCount = new AtomicLong();

  private final AtomicLong parsedSchemaCacheMissCount = new AtomicLong();

  private final AtomicLong referenceModuleCacheHitCount = new AtomicLong();

  private final AtomicLong referenceModuleCacheMissCount = new AtomicLong();

  private final AtomicLong moduleMetricsEvictionCount = new AtomicLong();

  private final AtomicLong validationErrorCount = new AtomicLong();

  public YangSchemaProviderMetrics(
      IntSupplier referenceModuleCacheSizeSupplier,
      IntSupplier parsedSchemaCacheSizeSupplier) {

    ObjectName name = buildObjectName(DOMAIN + ":type=SchemaCache");
    if (name != null) {
      registerMBean(name,
              new GlobalMetrics(
                  referenceModuleCacheSizeSupplier, parsedSchemaCacheSizeSupplier
//                  inFlightParseCountSupplier
              ),
              YangSchemaProviderMetricsMBean.class, false);
    }
  }

  public void recordRequest() {
    totalRequestCount.incrementAndGet();
  }

  public void recordReferenceModuleCacheEviction() {
    referenceModuleCacheEvictionCount.incrementAndGet();
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
    referenceModuleCacheHitCount.incrementAndGet();
  }

  public void recordReferenceCacheMiss() {
    referenceModuleCacheMissCount.incrementAndGet();
  }

  public void recordValidationError() {
    validationErrorCount.incrementAndGet();
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

    long getReferenceModuleCacheSize();

    long getReferenceModuleCacheEvictionCount();

    long getParsedSchemaCacheSize();

    long getParsedSchemaCacheEvictionCount();

    long getParsedSchemaCacheHitCount();

    long getParsedSchemaCacheMissCount();

    long getReferenceModuleCacheHitCount();

    long getReferenceModuleCacheMissCount();

    long getModuleMetricsEvictionCount();

    long getValidationErrorCount();

//    long getInFlightParseCount();
  }

  private class GlobalMetrics implements YangSchemaProviderMetricsMBean {
    private final IntSupplier referenceModuleCacheSizeSupplier;
    private final IntSupplier parsedSchemaCacheSizeSupplier;
//    private final IntSupplier inFlightParseCountSupplier;

    GlobalMetrics(
        IntSupplier referenceModuleCacheSizeSupplier,
        IntSupplier parsedSchemaCacheSizeSupplier
//        IntSupplier inFlightParseCountSupplier
    ) {
      this.referenceModuleCacheSizeSupplier = referenceModuleCacheSizeSupplier;
      this.parsedSchemaCacheSizeSupplier = parsedSchemaCacheSizeSupplier;
//      this.inFlightParseCountSupplier = inFlightParseCountSupplier;
    }

    @Override
    public long getTotalRequestCount() {
      return totalRequestCount.get();
    }

    @Override
    public long getReferenceModuleCacheSize() {
      return referenceModuleCacheSizeSupplier.getAsInt();
    }

    @Override
    public long getReferenceModuleCacheEvictionCount() {
      return referenceModuleCacheEvictionCount.get();
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
    public long getReferenceModuleCacheHitCount() {
      return referenceModuleCacheHitCount.get();
    }

    @Override
    public long getReferenceModuleCacheMissCount() {
      return referenceModuleCacheMissCount.get();
    }

    @Override
    public long getModuleMetricsEvictionCount() {
      return moduleMetricsEvictionCount.get();
    }

    @Override
    public long getValidationErrorCount() {
      return validationErrorCount.get();
    }

//    @Override
//    public long getInFlightParseCount() {
//      return inFlightParseCountSupplier.getAsInt();
//    }
  }
}

