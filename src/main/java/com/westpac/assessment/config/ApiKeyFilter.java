package com.westpac.assessment.config;

import static com.westpac.assessment.constants.ApiConstants.ACCOUNTS_PATH;
import static com.westpac.assessment.constants.ApiConstants.ACCOUNT_ITEM_PREFIX;
import static com.westpac.assessment.constants.ApiConstants.API_KEY_HEADER;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

public class ApiKeyFilter extends OncePerRequestFilter {
  private final byte[] expected;
  private final HandlerExceptionResolver resolver;

  public ApiKeyFilter(String key, HandlerExceptionResolver resolver) {
    if (key == null || key.isBlank())
      throw new IllegalArgumentException("API_KEY must not be blank");
    this.expected = key.getBytes(StandardCharsets.UTF_8);
    this.resolver = resolver;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    // MockMvc can leave servletPath empty; requestURI also includes contextPath.
    if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
    return !path.equals(ACCOUNTS_PATH) && !path.startsWith(ACCOUNT_ITEM_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String actual = request.getHeader(API_KEY_HEADER);
    if (actual == null
        || !MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.UTF_8))) {
      resolver.resolveException(
          request, response, null, new BadCredentialsException("Invalid API key"));
      return;
    }
    chain.doFilter(request, response);
  }
}
