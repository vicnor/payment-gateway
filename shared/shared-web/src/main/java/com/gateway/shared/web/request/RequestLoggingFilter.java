package com.gateway.shared.web.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.currentTimeMillis();
    try {
      chain.doFilter(request, response);
    } finally {
      long durationMs = System.currentTimeMillis() - start;
      // Log path only — never the query string (could contain session secrets, e.g. ?k=...)
      log.info(
          "method={} path={} status={} duration_ms={} requestId={}",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          durationMs,
          MDC.get(RequestIdFilter.MDC_KEY));
    }
  }
}
