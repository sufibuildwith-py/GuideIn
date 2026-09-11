package io.guidein.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("unit")
class ApiExceptionHandlerTest {
    @Test
    void unexpectedErrorsNeverExposeExceptionOrSecretCanary() {
        String canary = "GUIDEIN_SECRET_CANARY_7fce1b";
        var handler = new ApiExceptionHandler(new ProblemDetailsFactory());
        var request = new MockHttpServletRequest("POST", "/api/v1/repositories");

        var response = handler.unknown(new IllegalStateException("database password=" + canary), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("The request could not be completed.");
        assertThat(response.getBody().toString()).doesNotContain(canary).doesNotContain("password");
    }
}
