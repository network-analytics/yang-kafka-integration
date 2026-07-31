package ch.swisscom.kafka.schemaregistry.util;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
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

  public static final String METRICS_PER_MODULE_PROPERTY = "yang.monitor.metrics-per-module";

  private final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

  private final boolean perModuleMetricsEnabled;

  private final AtomicLong totalRequestCount = new AtomicLong();

  private final AtomicLong referenceModuleCacheEvictionCount = new AtomicLong();

  private final AtomicLong parsedSchemaCacheEvictionCount = new AtomicLong();

  private final AtomicLong parsedSchemaCacheHitCount = new AtomicLong();

  private final AtomicLong parsedSchemaCacheMissCount = new AtomicLong();

  private final AtomicLong referenceModuleCacheHitCount = new AtomicLong();

  private final AtomicLong referenceModuleCacheMissCount = new AtomicLong();

  private final AtomicLong moduleMetricsEvictionCount = new AtomicLong();

  private final AtomicLong validationErrorCount = new AtomicLong();

  private final BoundedCache<String, ModuleCacheMetrics> moduleCacheMetrics;

  public YangSchemaProviderMetrics(
      IntSupplier referenceModuleCacheSizeSupplier,
      IntSupplier parsedSchemaCacheSizeSupplier,
      int moduleMetricsMaxSize) {
    this.perModuleMetricsEnabled = Boolean.parseBoolean(System.getProperty(METRICS_PER_MODULE_PROPERTY, "true"));
    log.info("Monitoring per YANG-module metrics: {}", perModuleMetricsEnabled);

    this.moduleCacheMetrics = new BoundedCache<>(
        moduleMetricsMaxSize, 0L, moduleMetricsEvictionCount::incrementAndGet);

    ObjectName name = buildObjectName(DOMAIN + ":type=SchemaCache");
    if (name != null) {
      registerMBean(name,
              new GlobalMetrics(referenceModuleCacheSizeSupplier, parsedSchemaCacheSizeSupplier),
              YangSchemaProviderMetricsMBean.class, false);
    }

    if (perModuleMetricsEnabled) {
      ObjectName moduleReferenceMetricsName = buildObjectName(DOMAIN + ":type=ModuleReferenceMetrics");
      if (moduleReferenceMetricsName != null) {
        registerMBean(
            moduleReferenceMetricsName,
            new ResolvedReferenceMetrics(),
            ResolvedReferenceMetricsMXBean.class,
            true);
      }
    } else {
      log.info("Skipping ModuleReferenceMetrics MBean registration ({}=false) - "
          + "only aggregate SchemaCache metrics will be exposed.", METRICS_PER_MODULE_PROPERTY);
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

  public void recordParsedSchemaCacheHit(String moduleName) {
    parsedSchemaCacheHitCount.incrementAndGet();
    if (perModuleMetricsEnabled) {
      moduleMetrics(moduleName).parsedSchemaCacheHitCount.incrementAndGet();
    }
  }

  public void recordParsedSchemaCacheMiss(String moduleName) {
    parsedSchemaCacheMissCount.incrementAndGet();
    if (perModuleMetricsEnabled) {
      moduleMetrics(moduleName).parsedSchemaCacheMissCount.incrementAndGet();
    }
  }

  public void recordReferenceCacheHit(String moduleName) {
    referenceModuleCacheHitCount.incrementAndGet();
    if (perModuleMetricsEnabled) {
      moduleMetrics(moduleName).referenceHitCount.incrementAndGet();
    }
  }

  public void recordReferenceCacheMiss(String moduleName) {
    referenceModuleCacheMissCount.incrementAndGet();
    if (perModuleMetricsEnabled) {
      moduleMetrics(moduleName).referenceMissCount.incrementAndGet();
    }
  }

  public void recordValidationError(String moduleName) {
    validationErrorCount.incrementAndGet();
    if (perModuleMetricsEnabled) {
      moduleMetrics(moduleName).validationErrorCount.incrementAndGet();
    }
  }

  public void recordCompatibilityCheckFailure(String moduleName) {
    if (perModuleMetricsEnabled) {
      moduleMetrics(moduleName).compatibilityCheckFailureCount.incrementAndGet();
    }
  }

  public void recordResolvedReferences(String moduleName, int numResolvedReferences) {
    if (perModuleMetricsEnabled) {
      moduleMetrics(moduleName).numResolvedReferences.set(numResolvedReferences);
    }
  }

  public void recordParseLatency(String moduleName, long durationNanos) {
    if (perModuleMetricsEnabled) {
      ModuleCacheMetrics metrics = moduleMetrics(moduleName);
      metrics.parseCount.incrementAndGet();
      metrics.parseDurationNanos.addAndGet(durationNanos);
    }
  }

  public void recordCompatibilityCheckLatency(String moduleName, long durationNanos) {
    if (perModuleMetricsEnabled) {
      ModuleCacheMetrics metrics = moduleMetrics(moduleName);
      metrics.compatibilityCheckCount.incrementAndGet();
      metrics.compatibilityCheckDurationNanos.addAndGet(durationNanos);
    }
  }

  private ModuleCacheMetrics moduleMetrics(String moduleName) {
    return moduleCacheMetrics.getOrCreate(moduleName, ModuleCacheMetrics::new);
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

    long getModuleMetricsCacheSize();

    long getModuleMetricsEvictionCount();

    long getValidationErrorCount();

  }

  private class GlobalMetrics implements YangSchemaProviderMetricsMBean {
    private final IntSupplier referenceModuleCacheSizeSupplier;
    private final IntSupplier parsedSchemaCacheSizeSupplier;

    GlobalMetrics(IntSupplier referenceModuleCacheSizeSupplier, IntSupplier parsedSchemaCacheSizeSupplier) {
      this.referenceModuleCacheSizeSupplier = referenceModuleCacheSizeSupplier;
      this.parsedSchemaCacheSizeSupplier = parsedSchemaCacheSizeSupplier;
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
    public long getModuleMetricsCacheSize() {
      return moduleCacheMetrics.size();
    }

    @Override
    public long getModuleMetricsEvictionCount() {
      return moduleMetricsEvictionCount.get();
    }

    @Override
    public long getValidationErrorCount() {
      return validationErrorCount.get();
    }

  }

  public interface ModuleCacheMetricsMBean {
    long getReferenceHitCount();

    long getReferenceMissCount();

    long getValidationErrorCount();

    long getCompatibilityCheckFailureCount();

    long getNumResolvedReferences();

    long getParseCount();

    long getParseDurationNanos();

    long getCompatibilityCheckCount();

    long getCompatibilityCheckDurationNanos();

    long getParsedSchemaCacheHitCount();

    long getParsedSchemaCacheMissCount();
  }

  public interface ResolvedReferenceMetricsMXBean {
    Map<String, Long> getNumResolvedReferencesByModule();

    Map<String, Long> getReferenceHitCountByModule();

    Map<String, Long> getReferenceMissCountByModule();

    Map<String, Long> getParseCountByModule();

    Map<String, Long> getParseDurationNanosByModule();

    Map<String, Long> getCompatibilityCheckCountByModule();

    Map<String, Long> getCompatibilityCheckDurationNanosByModule();

    Map<String, Long> getParsedSchemaCacheHitCountByModule();

    Map<String, Long> getParsedSchemaCacheMissCountByModule();
  }

  private class ResolvedReferenceMetrics implements ResolvedReferenceMetricsMXBean {
    @Override
    public Map<String, Long> getNumResolvedReferencesByModule() {
      return snapshot(m -> m.numResolvedReferences.get());
    }

    @Override
    public Map<String, Long> getReferenceHitCountByModule() {
      return snapshot(m -> m.referenceHitCount.get());
    }

    @Override
    public Map<String, Long> getReferenceMissCountByModule() {
      return snapshot(m -> m.referenceMissCount.get());
    }

    @Override
    public Map<String, Long> getParseCountByModule() {
      return snapshot(m -> m.parseCount.get());
    }

    @Override
    public Map<String, Long> getParseDurationNanosByModule() {
      return snapshot(m -> m.parseDurationNanos.get());
    }

    @Override
    public Map<String, Long> getCompatibilityCheckCountByModule() {
      return snapshot(m -> m.compatibilityCheckCount.get());
    }

    @Override
    public Map<String, Long> getCompatibilityCheckDurationNanosByModule() {
      return snapshot(m -> m.compatibilityCheckDurationNanos.get());
    }

    @Override
    public Map<String, Long> getParsedSchemaCacheHitCountByModule() {
      return snapshot(m -> m.parsedSchemaCacheHitCount.get());
    }

    @Override
    public Map<String, Long> getParsedSchemaCacheMissCountByModule() {
      return snapshot(m -> m.parsedSchemaCacheMissCount.get());
    }

    private Map<String, Long> snapshot(java.util.function.Function<ModuleCacheMetrics, Long> valueExtractor) {
      Map<String, Long> snapshot = new LinkedHashMap<>();
      for (Map.Entry<String, ModuleCacheMetrics> entry : moduleCacheMetrics.asMap().entrySet()) {
        snapshot.put(entry.getKey(), valueExtractor.apply(entry.getValue()));
      }
      return snapshot;
    }
  }

  private static class ModuleCacheMetrics implements ModuleCacheMetricsMBean {
    private final AtomicLong referenceHitCount = new AtomicLong();
    private final AtomicLong referenceMissCount = new AtomicLong();
    private final AtomicLong validationErrorCount = new AtomicLong();
    private final AtomicLong compatibilityCheckFailureCount = new AtomicLong();
    private final AtomicLong numResolvedReferences = new AtomicLong();
    private final AtomicLong parseCount = new AtomicLong();
    private final AtomicLong parseDurationNanos = new AtomicLong();
    private final AtomicLong compatibilityCheckCount = new AtomicLong();
    private final AtomicLong compatibilityCheckDurationNanos = new AtomicLong();
    private final AtomicLong parsedSchemaCacheHitCount = new AtomicLong();
    private final AtomicLong parsedSchemaCacheMissCount = new AtomicLong();

    @Override
    public long getReferenceHitCount() {
      return referenceHitCount.get();
    }

    @Override
    public long getReferenceMissCount() {
      return referenceMissCount.get();
    }

    @Override
    public long getValidationErrorCount() {
      return validationErrorCount.get();
    }

    @Override
    public long getCompatibilityCheckFailureCount() {
      return compatibilityCheckFailureCount.get();
    }

    @Override
    public long getNumResolvedReferences() {
      return numResolvedReferences.get();
    }

    @Override
    public long getParseCount() {
      return parseCount.get();
    }

    @Override
    public long getParseDurationNanos() {
      return parseDurationNanos.get();
    }

    @Override
    public long getCompatibilityCheckCount() {
      return compatibilityCheckCount.get();
    }

    @Override
    public long getCompatibilityCheckDurationNanos() {
      return compatibilityCheckDurationNanos.get();
    }

    @Override
    public long getParsedSchemaCacheHitCount() {
      return parsedSchemaCacheHitCount.get();
    }

    @Override
    public long getParsedSchemaCacheMissCount() {
      return parsedSchemaCacheMissCount.get();
    }
  }
}

