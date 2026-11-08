package com.westpac.assessment;

import static com.westpac.assessment.TestConstants.ALICE_REQUEST;
import static com.westpac.assessment.TestConstants.ERROR_KEY;
import static com.westpac.assessment.TestConstants.JSON_STATUS_PATH;
import static com.westpac.assessment.TestConstants.TEST_API_KEY;
import static com.westpac.assessment.TestConstants.TEST_SUBJECT;
import static com.westpac.assessment.constants.ApiConstants.ACCOUNTS_PATH;
import static com.westpac.assessment.constants.ApiConstants.ACCOUNT_ITEM_PREFIX;
import static com.westpac.assessment.constants.ApiConstants.API_KEY_HEADER;
import static com.westpac.assessment.constants.ApiConstants.IDEMPOTENCY_KEY_HEADER;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.westpac.assessment.account_management.controller.*;
import com.westpac.assessment.account_management.domain.exceptions.BusinessException;
import com.westpac.assessment.account_management.domain.exceptions.ServiceUnavailableException;
import com.westpac.assessment.account_management.domain.service.*;
import com.westpac.assessment.config.ApplicationConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.test.context.ActiveProfiles("test")
@WebMvcTest(
    controllers = AccountController.class,
    properties = {"security.api-key=test-api-key"})
@Import({
  ApplicationConfig.class,
  AccountExceptionHandler.class,
  AccountControllerAdviceTest.TestCache.class
})
class AccountControllerAdviceTest {
  @org.springframework.boot.test.context.TestConfiguration
  static class TestCache {
    @org.springframework.context.annotation.Bean
    org.springframework.cache.CacheManager cacheManager() {
      return new org.springframework.cache.concurrent.ConcurrentMapCacheManager("accounts");
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean AccountService service;
  @MockitoBean JwtDecoder jwtDecoder;

  void expectError(RuntimeException error, int status) throws Exception {
    UUID id = UUID.randomUUID();
    when(service.getAccount(id, TEST_SUBJECT)).thenThrow(error);
    mvc.perform(
            get(ACCOUNT_ITEM_PREFIX + id)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .with(
                    jwt()
                        .jwt(j -> j.subject(TEST_SUBJECT))
                        .authorities(new SimpleGrantedAuthority("SCOPE_accounts:read"))))
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath(JSON_STATUS_PATH).value(status))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret internals"))));
  }

  @Test
  void postUnexpectedErrorReturns500() throws Exception {
    when(service.createAccount(any(), eq(ERROR_KEY), eq(TEST_SUBJECT)))
        .thenThrow(new NullPointerException("internal implementation"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ACCOUNTS_PATH)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(IDEMPOTENCY_KEY_HEADER, ERROR_KEY)
                .with(
                    jwt()
                        .jwt(j -> j.subject(TEST_SUBJECT))
                        .authorities(new SimpleGrantedAuthority("SCOPE_accounts:write")))
                .contentType(APPLICATION_JSON_VALUE)
                .content(ALICE_REQUEST))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath(JSON_STATUS_PATH).value(500));
  }

  @Test
  void postDatabaseUnavailableReturns503() throws Exception {
    when(service.createAccount(any(), eq(ERROR_KEY), eq(TEST_SUBJECT)))
        .thenThrow(new DataAccessResourceFailureException("database down"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ACCOUNTS_PATH)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(IDEMPOTENCY_KEY_HEADER, ERROR_KEY)
                .with(
                    jwt()
                        .jwt(j -> j.subject(TEST_SUBJECT))
                        .authorities(new SimpleGrantedAuthority("SCOPE_accounts:write")))
                .contentType(APPLICATION_JSON_VALUE)
                .content(ALICE_REQUEST))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath(JSON_STATUS_PATH).value(503));
  }

  @Test
  void getAccountNullPointerReturnsSanitized500() throws Exception {
    expectError(new NullPointerException("secret internals"), 500);
  }

  @Test
  void getAccountCodeErrorReturns500() throws Exception {
    expectError(new IllegalStateException("internal bug"), 500);
  }

  @Test
  void getAccountDatabaseUnavailableReturns503() throws Exception {
    expectError(new DataAccessResourceFailureException("database down"), 503);
  }

  @Test
  void getAccountRedisUnavailableReturns503() throws Exception {
    expectError(new RedisConnectionFailureException("redis down"), 503);
  }

  @Test
  void upstream503Returns503() throws Exception {
    expectError(
        org.springframework.web.client.HttpServerErrorException.create(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Unavailable",
            org.springframework.http.HttpHeaders.EMPTY,
            new byte[0],
            null),
        503);
  }

  @Test
  void explicitDependencyFailureReturns503() throws Exception {
    expectError(new ServiceUnavailableException("upstream down", null), 503);
  }

  @Test
  void timeoutReturns503() throws Exception {
    expectError(new QueryTimeoutException("timeout"), 503);
  }

  @Test
  void businessErrorReturns422() throws Exception {
    expectError(new BusinessException("Account limit reached"), 422);
  }

  @Test
  void constraintViolationReturns422() throws Exception {
    expectError(new DataIntegrityViolationException("constraint internal"), 422);
  }
}
