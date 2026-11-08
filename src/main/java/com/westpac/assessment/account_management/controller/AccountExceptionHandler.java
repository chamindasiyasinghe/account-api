package com.westpac.assessment.account_management.controller;

import static com.westpac.assessment.constants.ErrorMessages.INTERNAL_ERROR;
import static com.westpac.assessment.constants.ErrorMessages.SERVICE_UNAVAILABLE;

import com.westpac.assessment.account_management.domain.exceptions.BusinessException;
import com.westpac.assessment.account_management.domain.exceptions.ServiceUnavailableException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class AccountExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(AccountExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException ex) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    Map<String, String> errors = new LinkedHashMap<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
    problem.setProperty("errors", errors);
    return problem;
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class,
    HandlerMethodValidationException.class
  })
  ProblemDetail badRequest(Exception ex) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "Invalid request body, account ID or required header");
  }

  @ExceptionHandler(BusinessException.class)
  ProblemDetail business(BusinessException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(422), ex.getMessage());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail integrity(DataIntegrityViolationException ex) {
    log.warn("event=account_integrity_rejected status=422");
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.valueOf(422), "The request violates a data uniqueness or integrity rule");
  }

  @ExceptionHandler(AuthenticationException.class)
  ProblemDetail unauthorized(AuthenticationException ex, HttpServletResponse response) {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.UNAUTHORIZED, "Valid Bearer JWT and api_key headers are required");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail forbidden(AccessDeniedException ex) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.FORBIDDEN, "You do not have permission to perform this operation");
  }

  @ExceptionHandler({
    DataAccessResourceFailureException.class,
    CannotCreateTransactionException.class,
    QueryTimeoutException.class,
    RedisConnectionFailureException.class,
    ServiceUnavailableException.class,
    org.springframework.web.client.ResourceAccessException.class
  })
  ProblemDetail unavailable(Exception ex) {
    log.error("event=account_dependency_unavailable status=503");
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE);
  }

  @ExceptionHandler(org.springframework.web.client.RestClientResponseException.class)
  ProblemDetail upstream(org.springframework.web.client.RestClientResponseException ex) {
    log.error("event=account_upstream_failed");
    boolean unavailable = ex.getStatusCode().is5xxServerError();
    return ProblemDetail.forStatusAndDetail(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR,
        unavailable ? SERVICE_UNAVAILABLE : INTERNAL_ERROR);
  }

  @ExceptionHandler(ResponseStatusException.class)
  ProblemDetail status(ResponseStatusException ex) {
    if (ex.getStatusCode().is5xxServerError()) {
      log.error("event=account_service_failed");
      return ProblemDetail.forStatusAndDetail(
          ex.getStatusCode(),
          ex.getStatusCode().value() == 503 ? SERVICE_UNAVAILABLE : INTERNAL_ERROR);
    }
    return ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception ex) {
    log.error("event=account_internal_error status=500");
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR);
  }
}
