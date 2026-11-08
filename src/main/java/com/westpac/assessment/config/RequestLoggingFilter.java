package com.westpac.assessment.config;

import static com.westpac.assessment.constants.ApiConstants.ACCOUNTS_PATH;
import static com.westpac.assessment.constants.ApiConstants.ACCOUNT_ITEM_PREFIX;
import static com.westpac.assessment.constants.ApiConstants.REQUEST_ID_HEADER;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs only server-controlled metadata; never logs headers, bodies, query strings or IDs in URLs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);
  private static final String MDC_REQUEST_ID = "requestId";
  private static final Set<String> METHODS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString();
    String previousRequestId = MDC.get(MDC_REQUEST_ID);
    MDC.put(MDC_REQUEST_ID, requestId);
    response.setHeader(REQUEST_ID_HEADER, requestId);
    long started = System.nanoTime();
    boolean completed = false;
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
      int status = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
      LOG.info(
          "event=request_completed request_id={} method={} operation={} status={} duration_ms={}",
          requestId,
          method,
          operation(request),
          status,
          duration);
      if (previousRequestId == null) {
        MDC.remove(MDC_REQUEST_ID);
      } else {
        MDC.put(MDC_REQUEST_ID, previousRequestId);
      }
    }
  }

  private String operation(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (ACCOUNTS_PATH.equals(path) && "POST".equals(request.getMethod())) {
      return "create_account";
    }
    if (path.startsWith(ACCOUNT_ITEM_PREFIX) && "GET".equals(request.getMethod())) {
      return "get_account";
    }
    return "other";
  }
}
