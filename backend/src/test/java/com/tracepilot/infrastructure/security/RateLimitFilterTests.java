package com.tracepilot.infrastructure.security;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class RateLimitFilterTests {
    @Test
    void storageFailureReturns503WithoutExecutingBusinessWorkOrExposingConnectionDetails() throws Exception {
        var limiter = mock(RateLimiter.class);
        doThrow(new CannotCreateTransactionException("private-database-host-and-credential"))
            .when(limiter).require(anyString(), anyString(), anyInt(), anyInt());
        var properties = new RateLimitProperties(true, 60, 120, 600, 120, 10, 10);
        var filter = new RateLimitFilter(limiter, properties, new ObjectMapper());
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setAttribute("requestId", "fixture-request-id");
        var response = new MockHttpServletResponse();
        var proceeded = new AtomicBoolean();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> proceeded.set(true));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("private-database", "credential");
        assertThat(proceeded).isFalse();
    }
}
