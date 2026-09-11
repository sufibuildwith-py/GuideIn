package io.guidein.platform.infrastructure;

import io.guidein.platform.api.RequestIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdentityFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = RequestIdentity.class.getName();
    public static final String REQUEST_HEADER = "X-Request-Id";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID requestId = UUID.randomUUID();
        UUID correlationId = parseOrGenerate(request.getHeader(CORRELATION_HEADER));
        request.setAttribute(ATTRIBUTE, new RequestIdentity(requestId, correlationId));
        response.setHeader(REQUEST_HEADER, requestId.toString());
        response.setHeader(CORRELATION_HEADER, correlationId.toString());
        try (MDC.MDCCloseable ignoredRequest = MDC.putCloseable("request_id", requestId.toString());
             MDC.MDCCloseable ignoredCorrelation = MDC.putCloseable("correlation_id", correlationId.toString())) {
            chain.doFilter(request, response);
        }
    }

    private UUID parseOrGenerate(String value) {
        if (value == null || value.length() > 36) return UUID.randomUUID();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return UUID.randomUUID();
        }
    }
}

