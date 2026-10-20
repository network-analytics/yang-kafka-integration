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

  private final AtomicLong compatibleCount = new AtomicLong();
  private final AtomicLong incompatibleCount = new AtomicLong();

  private final ConcurrentHashMap<ParseErrorReason, AtomicLong> parseErrorCounts = new ConcurrentHashMap<>();

  public YangSchemaProviderMetrics() {
    ObjectName name = buildObjectName(DOMAIN + ":type=SchemaCache");
    if (name != null) {
      registerMBean(name, new GlobalMetrics(), YangSchemaProviderMetricsMBean.class, false);
    }

    for (ParseErrorReason reason : ParseErrorReason.values()) {
      getOrRegisterParseErrorCounter(reason);
    }
  }

  public void recordRequest() {
    totalRequestCount.incrementAndGet();
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

    long getCompatibleCount();

    long getIncompatibleCount();
  }

  public interface ParseErrorMetricsMBean {
    long getCount();
  }

  private class GlobalMetrics implements YangSchemaProviderMetricsMBean {

    @Override
    public long getTotalRequestCount() {
      return totalRequestCount.get();
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
