package com.westpac.assessment.constants;

public final class ApiConstants {
  public static final String ACCOUNTS_PATH = "/api/accounts";
  public static final String ACCOUNT_ITEM_PREFIX = ACCOUNTS_PATH + "/";
  public static final String ACCOUNT_BY_ID_PATH = ACCOUNT_ITEM_PREFIX + "{id}";
  public static final String API_KEY_HEADER = "api_key";
  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String BEARER_PREFIX = "Bearer ";
  public static final String READ_SCOPE = "accounts:read";
  public static final String WRITE_SCOPE = "accounts:write";

  private ApiConstants() {}
}
