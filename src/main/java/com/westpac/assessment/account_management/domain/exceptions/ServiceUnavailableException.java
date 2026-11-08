package com.westpac.assessment.account_management.domain.exceptions;

/** Use when an upstream service is unavailable; do not expose upstream response bodies. */
public class ServiceUnavailableException extends RuntimeException {
  public ServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
