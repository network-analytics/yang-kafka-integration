package ch.swisscom.kafka.schemaregistry.yang;


public class YangSchemaException extends IllegalArgumentException {
  public YangSchemaException(String message) {
    super(message);
  }

  public YangSchemaException(String message, Throwable cause) {
    super(message, cause);
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}

