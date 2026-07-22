package ch.swisscom.kafka.schemaregistry.util;

import java.lang.management.ManagementFactory;
import java.util.Collections;
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

  private static final int LINKED_HASH_MAP_DEFAULT_INITIAL_CAPACITY = 16;
  private static final float LINKED_HASH_MAP_DEFAULT_LOAD_FACTOR = 0.75f;
  private static final boolean LINKED_HASH_MAP_ACCESS_ORDER = true;

  private final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

  private final int moduleMetricsMaxSize;

  private final AtomicLong totalRequestCount = new AtomicLong();

  private final AtomicLong parsedSchemaCacheEvictionCount = new AtomicLong();

  private final AtomicLong referenceModuleCacheEvictionCount = new AtomicLong();

  private final AtomicLong moduleMetricsEvictionCount = new AtomicLong();

  private final AtomicLong validationErrorCount = new AtomicLong();

  private final Map<String, ModuleCacheMetrics> moduleCacheMetrics;

  public YangSchemaProviderMetrics(
      IntSupplier parsedSchemaCacheSizeSupplier,
      IntSupplier referenceModuleCacheSizeSupplier,
      int moduleMetricsMaxSize) {
    this.moduleMetricsMaxSize = moduleMetricsMaxSize;
    this.moduleCacheMetrics = Collections.synchronizedMap(
        new LinkedHashMap<>(
            LINKED_HASH_MAP_DEFAULT_INITIAL_CAPACITY,
            LINKED_HASH_MAP_DEFAULT_LOAD_FACTOR,
            LINKED_HASH_MAP_ACCESS_ORDER) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, ModuleCacheMetrics> eldest) {
            boolean shouldRemove = size() > YangSchemaProviderMetrics.this.moduleMetricsMaxSize;
            if (shouldRemove) {
              moduleMetricsEvictionCount.incrementAndGet();
              unregisterModuleCacheMetrics(eldest.getKey());
            }
            return shouldRemove;
          }
        });
    ObjectName name = buildObjectName(DOMAIN + ":type=SchemaCache");
    if (name != null) {
      registerMBean(
          name,
          new GlobalMetrics(parsedSchemaCacheSizeSupplier, referenceModuleCacheSizeSupplier),
          YangSchemaProviderMetricsMBean.class);
    }
  }

  public void recordRequest() {
    totalRequestCount.incrementAndGet();
  }

  public void recordParsedSchemaCacheEviction() {
    parsedSchemaCacheEvictionCount.incrementAndGet();
  }

  public void recordReferenceModuleCacheEviction() {
    referenceModuleCacheEvictionCount.incrementAndGet();
  }

  public void recordSchemaCacheHit(String moduleName) {
    moduleMetrics(moduleName).schemaHitCount.incrementAndGet();
  }

  public void recordSchemaCacheMiss(String moduleName) {
    moduleMetrics(moduleName).schemaMissCount.incrementAndGet();
  }

  public void recordReferenceCacheHit(String moduleName) {
    moduleMetrics(moduleName).referenceHitCount.incrementAndGet();
  }

  public void recordReferenceCacheMiss(String moduleName) {
    moduleMetrics(moduleName).referenceMissCount.incrementAndGet();
  }

  public void recordValidationError(String moduleName) {
    moduleMetrics(moduleName).validationErrorCount.incrementAndGet();
  }

  public void recordCompatibilityCheckFailure(String moduleName) {
    moduleMetrics(moduleName).compatibilityCheckFailureCount.incrementAndGet();
  }

  private ModuleCacheMetrics moduleMetrics(String moduleName) {
    return moduleCacheMetrics.computeIfAbsent(moduleName, this::createModuleCacheMetrics);
  }

  private ModuleCacheMetrics createModuleCacheMetrics(String moduleName) {
    ModuleCacheMetrics metrics = new ModuleCacheMetrics();
    ObjectName name =
        buildObjectName(DOMAIN + ":type=ModuleCache,module=" + ObjectName.quote(moduleName));
    if (name != null) {
      registerMBean(name, metrics, ModuleCacheMetricsMBean.class);
    }
    return metrics;
  }

  private static ObjectName buildObjectName(String name) {
    try {
      return new ObjectName(name);
    } catch (MalformedObjectNameException e) {
      log.warn("Failed to build JMX object name '{}'", name, e);
      return null;
    }
  }

  private <T> void registerMBean(ObjectName name, T mbean, Class<T> mbeanInterface) {
    try {
      if (mBeanServer.isRegistered(name)) {
        mBeanServer.unregisterMBean(name);
      }
      mBeanServer.registerMBean(new StandardMBean(mbean, mbeanInterface), name);
    } catch (Exception e) {
      log.warn("Failed to register JMX MBean '{}'", name, e);
    }
  }

  private void unregisterModuleCacheMetrics(String moduleName) {
    ObjectName name = buildObjectName(DOMAIN + ":type=ModuleCache,module=" + ObjectName.quote(moduleName));
    if (name == null) {
      return;
    }
    try {
      if (mBeanServer.isRegistered(name)) {
        mBeanServer.unregisterMBean(name);
      }
    } catch (Exception e) {
      log.warn("Failed to unregister JMX MBean '{}'", name, e);
    }
  }

  public interface YangSchemaProviderMetricsMBean {
    long getTotalRequestCount();

    long getParsedSchemaCacheSize();

    long getParsedSchemaCacheEvictionCount();

    long getReferenceModuleCacheSize();


    long getReferenceModuleCacheEvictionCount();

    long getModuleMetricsEvictionCount();

    long getValidationErrorCount();
  }

  private class GlobalMetrics implements YangSchemaProviderMetricsMBean {
    private final IntSupplier parsedSchemaCacheSizeSupplier;
    private final IntSupplier referenceModuleCacheSizeSupplier;

    GlobalMetrics(
        IntSupplier parsedSchemaCacheSizeSupplier, IntSupplier referenceModuleCacheSizeSupplier) {
      this.parsedSchemaCacheSizeSupplier = parsedSchemaCacheSizeSupplier;
      this.referenceModuleCacheSizeSupplier = referenceModuleCacheSizeSupplier;
    }

    @Override
    public long getTotalRequestCount() {
      return totalRequestCount.get();
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
    public long getReferenceModuleCacheSize() {
      return referenceModuleCacheSizeSupplier.getAsInt();
    }


    @Override
    public long getReferenceModuleCacheEvictionCount() {
      return referenceModuleCacheEvictionCount.get();
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
    long getSchemaHitCount();

    long getSchemaMissCount();

    long getReferenceHitCount();

    long getReferenceMissCount();

    long getValidationErrorCount();

    long getCompatibilityCheckFailureCount();
  }

  private static class ModuleCacheMetrics implements ModuleCacheMetricsMBean {
    private final AtomicLong schemaHitCount = new AtomicLong();
    private final AtomicLong schemaMissCount = new AtomicLong();
    private final AtomicLong referenceHitCount = new AtomicLong();
    private final AtomicLong referenceMissCount = new AtomicLong();
    private final AtomicLong validationErrorCount = new AtomicLong();
    private final AtomicLong compatibilityCheckFailureCount = new AtomicLong();

    @Override
    public long getSchemaHitCount() {
      return schemaHitCount.get();
    }

    @Override
    public long getSchemaMissCount() {
      return schemaMissCount.get();
    }

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
  }
}

