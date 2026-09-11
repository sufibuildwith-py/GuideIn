package io.guidein.platform.infrastructure;

import io.guidein.platform.api.ErrorCode;
import io.guidein.platform.api.GuideInException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {
    private final ProblemDetailsFactory problems;

    public ApiExceptionHandler(ProblemDetailsFactory problems) { this.problems = problems; }

    @ExceptionHandler(GuideInException.class)
    ResponseEntity<ProblemDetail> guideIn(GuideInException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.code().status())
                .body(problems.create(exception.code(), exception.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ignored, HttpServletRequest request) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(problems.create(code, code.title(), request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unknown(Exception ignored, HttpServletRequest request) {
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status())
                .body(problems.create(code, "The request could not be completed.", request));
    }
}

