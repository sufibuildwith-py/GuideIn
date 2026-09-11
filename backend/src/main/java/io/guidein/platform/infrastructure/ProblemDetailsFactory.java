package io.guidein.platform.infrastructure;

import io.guidein.platform.api.ErrorCode;
import io.guidein.platform.api.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public final class ProblemDetailsFactory {
    public ProblemDetail create(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create("urn:guidein:error:" + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("retryable", code.retryable());
        Object value = request.getAttribute(RequestIdentityFilter.ATTRIBUTE);
        if (value instanceof RequestIdentity identity) {
            problem.setProperty("request_id", identity.requestId());
            problem.setProperty("correlation_id", identity.correlationId());
        }
        return problem;
    }
}

