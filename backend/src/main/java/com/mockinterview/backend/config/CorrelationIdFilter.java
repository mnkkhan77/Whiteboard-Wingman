package com.mockinterview.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request a correlation id — reused from the client's X-Correlation-Id header if it
 * sent one, otherwise a fresh UUID — in MDC (key "traceId", see logging.pattern.level) and echoed
 * back as a response header. StudyPackService reads it off MDC to stamp an OutboxEvent, so it
 * travels on to the Kafka message a request triggers and back again in the backend's own consumer
 * (DocumentEventsListener) — not a replacement for real distributed tracing (no span tree, no
 * cross-service propagation into the Python doc-processor's own logs), just enough to grep one
 * request's logs across the async hop.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    static final String HEADER = "X-Correlation-Id";
    private static final int MAX_LENGTH = 100;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** A client-supplied id ends up in logs and a Kafka header, so it's never trusted verbatim:
     *  control characters (which could forge extra log lines) are stripped and it's length-capped. */
    private static String sanitize(String incoming) {
        if (incoming == null) {
            return null;
        }
        String cleaned = incoming.replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= MAX_LENGTH ? cleaned : cleaned.substring(0, MAX_LENGTH);
    }
}
