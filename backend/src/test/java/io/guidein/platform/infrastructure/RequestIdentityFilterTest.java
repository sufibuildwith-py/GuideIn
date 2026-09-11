package io.guidein.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("unit")
class RequestIdentityFilterTest {
    private final RequestIdentityFilter filter = new RequestIdentityFilter();

    @Test
    void preservesOnlyValidCorrelationIdentifiersAndAlwaysCreatesRequestIdentifier() throws Exception {
        UUID correlation = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdentityFilter.CORRELATION_HEADER, correlation.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader(RequestIdentityFilter.CORRELATION_HEADER)).isEqualTo(correlation.toString());
        assertThatCodeIsUuid(response.getHeader(RequestIdentityFilter.REQUEST_HEADER));
    }

    @Test
    void rejectsMalformedIncomingCorrelationIdentifier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdentityFilter.CORRELATION_HEADER, "not-a-correlation-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader(RequestIdentityFilter.CORRELATION_HEADER)).isNotEqualTo("not-a-correlation-id");
        assertThatCodeIsUuid(response.getHeader(RequestIdentityFilter.CORRELATION_HEADER));
    }

    private void assertThatCodeIsUuid(String value) {
        assertThat(UUID.fromString(value)).isNotNull();
    }
}

