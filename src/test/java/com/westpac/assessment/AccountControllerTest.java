package com.westpac.assessment;

import static com.westpac.assessment.TestConstants.ALICE_NICKNAME_PREFIX;
import static com.westpac.assessment.TestConstants.ALICE_REQUEST;
import static com.westpac.assessment.TestConstants.INVALID_VALUE;
import static com.westpac.assessment.TestConstants.JSON_STATUS_PATH;
import static com.westpac.assessment.TestConstants.JSON_STRING_END;
import static com.westpac.assessment.TestConstants.SAME_KEY;
import static com.westpac.assessment.TestConstants.TEST_API_KEY;
import static com.westpac.assessment.TestConstants.TEST_AUDIENCE;
import static com.westpac.assessment.TestConstants.TEST_CUSTOMER_NAME;
import static com.westpac.assessment.TestConstants.TEST_ISSUER;
import static com.westpac.assessment.TestConstants.TEST_SUBJECT;
import static com.westpac.assessment.constants.ApiConstants.ACCOUNTS_PATH;
import static com.westpac.assessment.constants.ApiConstants.ACCOUNT_BY_ID_PATH;
import static com.westpac.assessment.constants.ApiConstants.ACCOUNT_ITEM_PREFIX;
import static com.westpac.assessment.constants.ApiConstants.API_KEY_HEADER;
import static com.westpac.assessment.constants.ApiConstants.BEARER_PREFIX;
import static com.westpac.assessment.constants.ApiConstants.IDEMPOTENCY_KEY_HEADER;
import static com.westpac.assessment.constants.ApiConstants.READ_SCOPE;
import static com.westpac.assessment.constants.ApiConstants.WRITE_SCOPE;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import com.westpac.assessment.account_management.domain.model.IdempotencyRecord;
import com.westpac.assessment.account_management.domain.repository.*;
import com.westpac.assessment.account_management.domain.service.CustomerAccountLock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:accounts;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.flyway.enabled=false",
      "spring.cache.type=simple",
      "security.api-key=test-api-key",
      "security.jwt.secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
      "security.jwt.issuer=assessment",
      "security.jwt.audience=assessment-api"
    })
@AutoConfigureMockMvc
class AccountControllerTest {
  @Autowired MockMvc mvc;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired AccountRepository accounts;
  @Autowired IdempotencyRepository idempotency;
  @Autowired CacheManager cacheManager;
  @MockitoBean CustomerAccountLock customerLock;
  static final String SECRET = "01234567890123456789012345678901";

  @BeforeEach
  void reset() {
    idempotency.deleteAll();
    accounts.deleteAll();
    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  static String token(
      String subject, String scope, Instant expires, String secret, String issuer, String audience)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(issuer)
            .audience(audience)
            .issueTime(Date.from(Instant.now().minusSeconds(10)))
            .expirationTime(expires == null ? null : Date.from(expires))
            .claim("scope", scope)
            .build();
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  static String token(String subject, String scope) throws Exception {
    return token(
        subject, scope, Instant.now().plusSeconds(600), SECRET, TEST_ISSUER, TEST_AUDIENCE);
  }

  ResultActions create(String body, String key, String subject) throws Exception {
    return mvc.perform(
        post(ACCOUNTS_PATH)
            .header(API_KEY_HEADER, TEST_API_KEY)
            .header(AUTHORIZATION, BEARER_PREFIX + token(subject, "accounts:write accounts:read"))
            .header(IDEMPOTENCY_KEY_HEADER, key)
            .contentType(APPLICATION_JSON_VALUE)
            .content(body));
  }

  ResultActions read(String path, String subject) throws Exception {
    return mvc.perform(
        get(path)
            .header(API_KEY_HEADER, TEST_API_KEY)
            .header(AUTHORIZATION, BEARER_PREFIX + token(subject, READ_SCOPE)));
  }

  @Test
  void createsAccountWith201AndRetrievesWith200AndCachesResult() throws Exception {
    var created =
        create(
                "{\"customerName\":\"Alice\",\"accountNickName\":\"Holiday\"}",
                "create-1",
                TEST_SUBJECT)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accountNumber").isNotEmpty())
            .andReturn();
    String path = created.getResponse().getHeader(LOCATION);
    read(path, TEST_SUBJECT)
        .andExpect(status().isOk())
        .andExpect(content().json(created.getResponse().getContentAsString()));
    var id = accounts.findAll().get(0).getId();
    assertThat(cacheManager.getCache("accounts").get(id + ":alice")).isNotNull();
    jdbc.update(
        "update savings_accounts set customer_name = ? where id = ?", "Changed in database", id);
    read(path, TEST_SUBJECT)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customerName").value(TEST_CUSTOMER_NAME));
    cacheManager.getCache("accounts").clear();
    read(path, TEST_SUBJECT)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customerName").value("Changed in database"));
  }

  @Test
  void rejectsMissingBlankAndOversizedCustomerName() throws Exception {
    for (String body :
        new String[] {
          "{}",
          "{\"customerName\":null}",
          "{\"customerName\":\" \"}",
          "{\"customerName\":\"" + "a".repeat(201) + JSON_STRING_END
        }) {
      create(body, UUID.randomUUID().toString(), TEST_SUBJECT).andExpect(status().isBadRequest());
    }
    assertThat(accounts.count()).isZero();
  }

  @Test
  void validatesOptionalNicknameAndBoundaries() throws Exception {
    create(ALICE_REQUEST, "absent", TEST_SUBJECT).andExpect(status().isCreated());
    create("{\"customerName\":\"Alice\",\"accountNickName\":null}", "null", TEST_SUBJECT)
        .andExpect(status().isCreated());
    for (String nick : new String[] {"", "    ", "abcd", "a".repeat(31)}) {
      create(
              ALICE_NICKNAME_PREFIX + nick + JSON_STRING_END,
              UUID.randomUUID().toString(),
              TEST_SUBJECT)
          .andExpect(status().isBadRequest());
    }
    for (int size : new int[] {5, 30})
      create(
              ALICE_NICKNAME_PREFIX + "a".repeat(size) + JSON_STRING_END,
              "nick-" + size,
              TEST_SUBJECT)
          .andExpect(status().isCreated());
  }

  @Test
  void rejectsSixthAccountWith422ButStillReplaysOriginalRequest() throws Exception {
    for (int i = 0; i < 5; i++)
      create("{\"customerName\":\"Alice Smith\"}", "key-" + i, TEST_SUBJECT)
          .andExpect(status().isCreated());
    create("{\"customerName\":\"  ALICE   smith  \"}", "sixth", TEST_SUBJECT)
        .andExpect(status().is(422));
    create("{\"customerName\":\"Alice Smith\"}", "key-0", TEST_SUBJECT)
        .andExpect(status().isCreated());
    assertThat(accounts.count()).isEqualTo(5);
    assertThat(idempotency.count()).isEqualTo(5);
  }

  @Test
  void sameKeySameBodyReturnsSameAccountAndLocation() throws Exception {
    String body = ALICE_REQUEST;
    var original =
        create(body, "retry-key", TEST_SUBJECT)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    create(body, "retry-key", TEST_SUBJECT)
        .andExpect(status().isCreated())
        .andExpect(header().string(LOCATION, original.getHeader(LOCATION)))
        .andExpect(content().json(original.getContentAsString()));
    assertThat(accounts.count()).isEqualTo(1);
    assertThat(idempotency.count()).isEqualTo(1);
  }

  @Test
  void sameKeyDifferentPayloadReturns422() throws Exception {
    create(ALICE_REQUEST, SAME_KEY, TEST_SUBJECT).andExpect(status().isCreated());
    create("{\"customerName\":\"Bob\"}", SAME_KEY, TEST_SUBJECT).andExpect(status().is(422));
    assertThat(accounts.count()).isEqualTo(1);
  }

  @Test
  void idempotencyKeyIsScopedToCaller() throws Exception {
    create(ALICE_REQUEST, SAME_KEY, TEST_SUBJECT).andExpect(status().isCreated());
    create("{\"customerName\":\"Bob\"}", SAME_KEY, "bob").andExpect(status().isCreated());
    assertThat(accounts.count()).isEqualTo(2);
  }

  @Test
  void databaseRejectsDuplicateCallerAndKey() throws Exception {
    create(ALICE_REQUEST, "unique-key", TEST_SUBJECT).andExpect(status().isCreated());
    var idempotencyRecord = idempotency.findAll().get(0);
    assertThatThrownBy(
            () ->
                idempotency.saveAndFlush(
                    new IdempotencyRecord(
                        TEST_SUBJECT,
                        "unique-key",
                        idempotencyRecord.getRequestHash(),
                        idempotencyRecord.getAccountId())))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(idempotency.count()).isEqualTo(1);
  }

  @Test
  void rejectsMissingBlankAndOversizedIdempotencyKey() throws Exception {
    mvc.perform(
            post(ACCOUNTS_PATH)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(AUTHORIZATION, BEARER_PREFIX + token(TEST_SUBJECT, WRITE_SCOPE))
                .contentType(APPLICATION_JSON_VALUE)
                .content(ALICE_REQUEST))
        .andExpect(status().isBadRequest());
    for (String key : new String[] {" ", "a".repeat(129)})
      create(ALICE_REQUEST, key, TEST_SUBJECT).andExpect(status().isBadRequest());
  }

  @Test
  void missingOrInvalidCredentialsReturn401() throws Exception {
    mvc.perform(get(ACCOUNT_ITEM_PREFIX + UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath(JSON_STATUS_PATH).value(401));
    mvc.perform(get(ACCOUNT_ITEM_PREFIX + UUID.randomUUID()).header(API_KEY_HEADER, TEST_API_KEY))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"));
    for (String key : new String[] {INVALID_VALUE, ""})
      mvc.perform(
              get(ACCOUNT_ITEM_PREFIX + UUID.randomUUID())
                  .header(API_KEY_HEADER, key)
                  .header(AUTHORIZATION, BEARER_PREFIX + token(TEST_SUBJECT, READ_SCOPE)))
          .andExpect(status().isUnauthorized());
    mvc.perform(
            get(ACCOUNT_ITEM_PREFIX + UUID.randomUUID())
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(AUTHORIZATION, "Bearer invalid"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsExpiredTamperedAndWrongIssuerAudienceTokens() throws Exception {
    String[] tokens = {
      token(
          TEST_SUBJECT,
          READ_SCOPE,
          Instant.now().minusSeconds(180),
          SECRET,
          TEST_ISSUER,
          TEST_AUDIENCE),
      token(
          TEST_SUBJECT,
          READ_SCOPE,
          Instant.now().plusSeconds(600),
          "11234567890123456789012345678901",
          TEST_ISSUER,
          TEST_AUDIENCE),
      token(
          TEST_SUBJECT,
          READ_SCOPE,
          Instant.now().plusSeconds(600),
          SECRET,
          INVALID_VALUE,
          TEST_AUDIENCE),
      token(
          TEST_SUBJECT,
          READ_SCOPE,
          Instant.now().plusSeconds(600),
          SECRET,
          TEST_ISSUER,
          INVALID_VALUE)
    };
    for (String jwt : tokens)
      mvc.perform(
              get(ACCOUNT_ITEM_PREFIX + UUID.randomUUID())
                  .header(API_KEY_HEADER, TEST_API_KEY)
                  .header(AUTHORIZATION, BEARER_PREFIX + jwt))
          .andExpect(status().isUnauthorized());
  }

  @Test
  void insufficientScopeReturns403() throws Exception {
    mvc.perform(
            post(ACCOUNTS_PATH)
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(AUTHORIZATION, BEARER_PREFIX + token(TEST_SUBJECT, READ_SCOPE))
                .header(IDEMPOTENCY_KEY_HEADER, "scope-key")
                .contentType(APPLICATION_JSON_VALUE)
                .content(ALICE_REQUEST))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(JSON_STATUS_PATH).value(403));
    mvc.perform(
            get(ACCOUNT_ITEM_PREFIX + UUID.randomUUID())
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(AUTHORIZATION, BEARER_PREFIX + token(TEST_SUBJECT, WRITE_SCOPE)))
        .andExpect(status().isForbidden());
  }

  @Test
  void otherCallerCannotReadAccountEvenAfterCacheHit() throws Exception {
    String path =
        create(ALICE_REQUEST, "owner-key", TEST_SUBJECT)
            .andReturn()
            .getResponse()
            .getHeader(LOCATION);
    read(path, TEST_SUBJECT).andExpect(status().isOk());
    read(path, "bob").andExpect(status().isForbidden());
  }

  @Test
  void handles404InvalidUuidAndMalformedBody() throws Exception {
    read(ACCOUNT_ITEM_PREFIX + UUID.randomUUID(), TEST_SUBJECT).andExpect(status().isNotFound());
    read("/api/accounts/not-a-uuid", TEST_SUBJECT).andExpect(status().isBadRequest());
    create("{", "bad-json", TEST_SUBJECT).andExpect(status().isBadRequest());
  }

  @Test
  void rejectsOffensiveNicknamesWith422WithoutSavingAndKeyRemainsReusable() throws Exception {
    for (String nickname : new String[] {"IDIOT savings", "offensive-nickname", "ＳＴＵＰＩＤ savings"}) {
      create(ALICE_NICKNAME_PREFIX + nickname + JSON_STRING_END, "policy-key", TEST_SUBJECT)
          .andExpect(status().is(422))
          .andExpect(
              jsonPath("$.detail").value("Account nickname contains a prohibited word or phrase"));
    }
    assertThat(accounts.count()).isZero();
    assertThat(idempotency.count()).isZero();
    create(
            "{\"customerName\":\"Alice\",\"accountNickName\":\"Holiday savings\"}",
            "policy-key",
            TEST_SUBJECT)
        .andExpect(status().isCreated());
  }

  @Test
  void generatesUniqueIdsAndAccountNumbersAndPersistsInputFields() throws Exception {
    for (int i = 0; i < 2; i++)
      create(
              "{\"customerName\":\"Alice\",\"accountNickName\":\"Holiday savings\"}",
              "unique-" + i,
              TEST_SUBJECT)
          .andExpect(status().isCreated());
    var stored = accounts.findAll();
    assertThat(stored).hasSize(2);
    assertThat(stored).extracting(a -> a.getId()).doesNotHaveDuplicates().doesNotContainNull();
    assertThat(stored)
        .extracting(a -> a.getAccountNumber())
        .doesNotHaveDuplicates()
        .doesNotContainNull();
    assertThat(stored)
        .allSatisfy(
            a -> {
              assertThat(a.getCustomerName()).isEqualTo(TEST_CUSTOMER_NAME);
              assertThat(a.getAccountNickName()).isEqualTo("Holiday savings");
            });
  }

  @Test
  void getAccountReturns400ForInvalidId() throws Exception {
    read("/api/accounts/invalid-uuid", TEST_SUBJECT)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath(JSON_STATUS_PATH).value(400));
  }

  @Test
  void getAccountReturns401ForMissingJwt() throws Exception {
    mvc.perform(get(ACCOUNT_ITEM_PREFIX + UUID.randomUUID()).header(API_KEY_HEADER, TEST_API_KEY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath(JSON_STATUS_PATH).value(401));
  }

  @Test
  void getAccountReturns403ForMissingReadScope() throws Exception {
    mvc.perform(
            get(ACCOUNT_ITEM_PREFIX + UUID.randomUUID())
                .header(API_KEY_HEADER, TEST_API_KEY)
                .header(AUTHORIZATION, BEARER_PREFIX + token(TEST_SUBJECT, WRITE_SCOPE)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath(JSON_STATUS_PATH).value(403));
  }

  @Test
  void getAccountRejectsMissingBlankOversizedSubjectAndMissingExpiration() throws Exception {
    for (String jwt :
        new String[] {
          token(null, READ_SCOPE),
          token("", READ_SCOPE),
          token("a".repeat(201), READ_SCOPE),
          token("legacy-unclaimed", READ_SCOPE),
          token(TEST_SUBJECT, READ_SCOPE, null, SECRET, TEST_ISSUER, TEST_AUDIENCE)
        }) {
      mvc.perform(
              get(ACCOUNT_ITEM_PREFIX + UUID.randomUUID())
                  .header(API_KEY_HEADER, TEST_API_KEY)
                  .header(AUTHORIZATION, BEARER_PREFIX + jwt))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void swaggerListsEveryGetResponseOnTheGetOperation() throws Exception {
    for (String code : new String[] {"200", "400", "401", "403", "404", "500", "503"}) {
      mvc.perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.paths['/api/accounts/{id}'].get.responses['" + code + "']").exists());
    }
  }

  @Test
  void swaggerDocumentsSecurityHeadersAndErrorResponses() throws Exception {
    var docs =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(docs)
        .contains(
            "bearerAuth",
            "apiKeyAuth",
            API_KEY_HEADER,
            IDEMPOTENCY_KEY_HEADER,
            "422",
            "401",
            "403",
            "500",
            "503",
            ACCOUNT_BY_ID_PATH);
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }
}
