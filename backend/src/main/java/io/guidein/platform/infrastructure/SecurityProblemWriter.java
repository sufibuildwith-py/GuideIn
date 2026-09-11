package io.guidein.platform.infrastructure;

import tools.jackson.databind.ObjectMapper;
import io.guidein.platform.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public final class SecurityProblemWriter {
    private final ObjectMapper mapper;
    private final ProblemDetailsFactory problems;

    public SecurityProblemWriter(ObjectMapper mapper, ProblemDetailsFactory problems) {
        this.mapper = mapper;
        this.problems = problems;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problems.create(code, code.title(), request));
    }
}
