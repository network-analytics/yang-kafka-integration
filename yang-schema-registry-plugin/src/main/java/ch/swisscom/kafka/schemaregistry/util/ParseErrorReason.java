package ch.swisscom.kafka.schemaregistry.util;

public enum ParseErrorReason {
  PARSE_SCHEMA("parseSchema"),

  UNRESOLVABLE_REFERENCE("unresolvableReference"),

  UNRESOLVED_IMPORTS("unresolvedImports"),

  VALIDATION_ERROR("validationError"),

  UNEXPECTED("unexpected");

  private final String label;

  ParseErrorReason(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}

