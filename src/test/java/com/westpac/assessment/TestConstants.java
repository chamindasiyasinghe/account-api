package com.westpac.assessment;

public final class TestConstants {
  public static final String TEST_SUBJECT = "alice";
  public static final String TEST_CUSTOMER_NAME = "Alice";
  public static final String TEST_OWNER = "owner";
  public static final String TEST_REQUEST_KEY = "key";
  public static final String TEST_API_KEY = "test-api-key";
  public static final String TEST_ISSUER = "assessment";
  public static final String TEST_AUDIENCE = "assessment-api";
  public static final String JSON_STATUS_PATH = "$.status";
  public static final String ERROR_KEY = "error-key";
  public static final String SAME_KEY = "same-key";
  public static final String INVALID_VALUE = "wrong";
  public static final String ALICE_REQUEST = "{\"customerName\":\"Alice\"}";
  public static final String ALICE_NICKNAME_PREFIX =
      "{\"customerName\":\"Alice\",\"accountNickName\":\"";
  public static final String JSON_STRING_END = "\"}";
  public static final String SWAGGER_ENABLED_PROPERTY = "springdoc.swagger-ui.enabled";

  private TestConstants() {}
}
