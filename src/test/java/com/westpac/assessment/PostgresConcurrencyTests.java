package com.westpac.assessment;

import static com.westpac.assessment.TestConstants.JSON_STRING_END;
import static com.westpac.assessment.TestConstants.SAME_KEY;
import static com.westpac.assessment.TestConstants.TEST_API_KEY;
import static com.westpac.assessment.TestConstants.TEST_CUSTOMER_NAME;
import static com.westpac.assessment.TestConstants.TEST_SUBJECT;
import static com.westpac.assessment.constants.ApiConstants.ACCOUNTS_PATH;
import static com.westpac.assessment.constants.ApiConstants.API_KEY_HEADER;
import static com.westpac.assessment.constants.ApiConstants.BEARER_PREFIX;
import static com.westpac.assessment.constants.ApiConstants.IDEMPOTENCY_KEY_HEADER;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.westpac.assessment.account_management.domain.repository.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;

/** Run only against a disposable, dedicated PostgreSQL test database. */
@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(
    properties = {
      "spring.flyway.enabled=true",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.cache.type=simple",
      "security.api-key=test-api-key",
      "security.jwt.secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
      "security.jwt.issuer=assessment",
      "security.jwt.audience=assessment-api"
    })
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class PostgresConcurrencyTests {
  @Autowired MockMvc mvc;
  @Autowired AccountRepository accounts;
  @Autowired IdempotencyRepository idempotency;

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("POSTGRES_TEST_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "postgres"));
    registry.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
  }

  @BeforeEach
  void clear() {
    idempotency.deleteAll();
    accounts.deleteAll();
  }

  List<MvcResult> concurrent(boolean sameKey, boolean changedBody) throws Exception {
    String token = AccountControllerTest.token(TEST_SUBJECT, "accounts:write accounts:read");
    ExecutorService pool = Executors.newFixedThreadPool(10);
    CountDownLatch ready = new CountDownLatch(10);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MvcResult>> futures = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        int index = i;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Start gate timed out");
                  return mvc.perform(
                          post(ACCOUNTS_PATH)
                              .header(API_KEY_HEADER, TEST_API_KEY)
                              .header(AUTHORIZATION, BEARER_PREFIX + token)
                              .header(IDEMPOTENCY_KEY_HEADER, sameKey ? SAME_KEY : "key-" + index)
                              .contentType(APPLICATION_JSON_VALUE)
                              .content(
                                  "{\"customerName\":\""
                                      + (changedBody ? "Customer " + index : TEST_CUSTOMER_NAME)
                                      + JSON_STRING_END))
                      .andReturn();
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<MvcResult> results = new ArrayList<>();
      for (var future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void simultaneousSameKeyCreatesOneAccount() throws Exception {
    var results = concurrent(true, false);
    assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
    assertThat(results.stream().map(r -> r.getResponse().getHeader(LOCATION)).distinct())
        .hasSize(1);
    assertThat(accounts.count()).isEqualTo(1);
    assertThat(idempotency.count()).isEqualTo(1);
  }

  @Test
  void simultaneousDifferentKeysNeverExceedFiveAccounts() throws Exception {
    var statuses = concurrent(false, false).stream().map(r -> r.getResponse().getStatus()).toList();
    assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5);
    assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(5);
    assertThat(accounts.count()).isEqualTo(5);
    assertThat(idempotency.count()).isEqualTo(5);
  }

  @Test
  void simultaneousSameKeyDifferentBodiesAcceptsOnlyOne() throws Exception {
    var statuses = concurrent(true, true).stream().map(r -> r.getResponse().getStatus()).toList();
    assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
    assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(9);
    assertThat(accounts.count()).isEqualTo(1);
    assertThat(idempotency.count()).isEqualTo(1);
  }
}
