package com.westpac.assessment.account_management.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

class SafeExceptionLoggingTest {
  @Test
  void neverLogsExceptionMessagesSqlValuesOrUpstreamBodies() {
    String sensitiveMarker = "private-customer-token-sql-body-marker";
    Logger logger = (Logger) LoggerFactory.getLogger(AccountExceptionHandler.class);
    var previousLevel = logger.getLevel();
    var events = new ListAppender<ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    logger.setLevel(ch.qos.logback.classic.Level.INFO);
    try {
      var advice = new AccountExceptionHandler();
      advice.unexpected(new NullPointerException(sensitiveMarker));
      advice.integrity(new DataIntegrityViolationException(sensitiveMarker));
      advice.unavailable(new DataAccessResourceFailureException(sensitiveMarker));
      advice.upstream(
          HttpServerErrorException.create(
              HttpStatus.SERVICE_UNAVAILABLE,
              sensitiveMarker,
              HttpHeaders.EMPTY,
              sensitiveMarker.getBytes(StandardCharsets.UTF_8),
              StandardCharsets.UTF_8));
      assertThat(events.list)
          .hasSize(4)
          .allSatisfy(
              event -> {
                assertThat(event.getFormattedMessage())
                    .startsWith("event=")
                    .doesNotContain(sensitiveMarker);
                assertThat(event.getThrowableProxy()).isNull();
              });
    } finally {
      logger.detachAppender(events);
      logger.setLevel(previousLevel);
      events.stop();
    }
  }
}
