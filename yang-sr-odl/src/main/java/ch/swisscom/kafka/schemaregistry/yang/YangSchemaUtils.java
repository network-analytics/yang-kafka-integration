package ch.swisscom.kafka.schemaregistry.yang;

import org.opendaylight.yangtools.yang.model.api.meta.EffectiveStatement;

// TODO: disable it in production - recursive tree walk, heavy for large modules
public final class YangSchemaUtils {
  private YangSchemaUtils() {
  }

  public static long countStatements(EffectiveStatement<?, ?> statement) {
    long count = 1;
    for (EffectiveStatement<?, ?> child : statement.effectiveSubstatements()) {
      count += countStatements(child);
    }
    return count;
  }
}

