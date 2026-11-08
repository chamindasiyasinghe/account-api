package com.westpac.assessment;

import static com.westpac.assessment.constants.ApiConstants.REQUEST_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.westpac.assessment.config.RequestLoggingFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTest {
  private static final String UNTRUSTED_ID = "untrusted-request-id";
  private static final String REQUEST_ID_MDC_KEY = "requestId";
  private ch.qos.logback.classic.Level previousLevel;
  private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void startCapturing() {
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    previousLevel = logger.getLevel();
    logger.setLevel(ch.qos.logback.classic.Level.INFO);
  }

  @AfterEach
  void stopCapturing() {
    logger.detachAppender(appender);
    logger.setLevel(previousLevel);
    appender.stop();
    MDC.clear();
  }

  @Test
  void logsOnlySafeMetadataAndGeneratesItsOwnRequestId() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/accounts");
    request.addHeader("Authorization", "Bearer secret-jwt-marker");
    request.addHeader("api_key", "secret-api-marker");
    request.addHeader("Idempotency-Key", "secret-retry-marker");
    request.addHeader(REQUEST_ID_HEADER, UNTRUSTED_ID);
    request.setQueryString("customerName=secret-query-marker");
    request.setContent("secret-body-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    new RequestLoggingFilter().doFilter(request, response, (req, res) -> response.setStatus(201));
    String generatedId = response.getHeader(REQUEST_ID_HEADER);
    assertThat(java.util.UUID.fromString(generatedId)).isNotNull();
    assertThat(generatedId).isNotEqualTo(UNTRUSTED_ID);
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("create_account", "status=201", generatedId)
        .doesNotContain("secret-", UNTRUSTED_ID, "customerName", "/api/accounts");
    assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isNull();
  }

  @Test
  void exceptionPayloadIsNotLoggedAndMdcIsRestored() {
    MDC.put(REQUEST_ID_MDC_KEY, "previous-value");
    var request = new MockHttpServletRequest("GET", "/api/accounts/private-account-id");
    var response = new MockHttpServletResponse();
    assertThatThrownBy(
            () ->
                new RequestLoggingFilter()
                    .doFilter(
                        request,
                        response,
                        (req, res) -> {
                          throw new ServletException("private-exception-payload");
                        }))
        .isInstanceOf(ServletException.class);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("get_account", "status=500")
        .doesNotContain("private-account-id", "private-exception-payload");
    assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isEqualTo("previous-value");
  }
}
