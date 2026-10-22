package dev.atlas.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class TracingFilterTest {

    @Test
    void fixedOperationPreservesTracingAndMarksErrorResponsesWithoutQueryData()
        throws Exception {
        var telemetry = mock(Telemetry.class);
        var span = mock(Span.class);
        when(span.getSpanContext()).thenReturn(SpanContext.getInvalid());
        when(span.makeCurrent()).thenReturn(Scope.noop());
        var request = new MockHttpServletRequest(
            "POST",
            "/api/payments/private-id/refund"
        );
        request.setQueryString("credential=secret-query");
        when(
            telemetry.incoming("HTTP POST /api/payments/{id}/refund", request)
        ).thenReturn(span);
        var response = new MockHttpServletResponse();
        new TracingFilter(telemetry).doFilter(request, response, (req, res) ->
            response.setStatus(503)
        );
        verify(telemetry).incoming(
            "HTTP POST /api/payments/{id}/refund",
            request
        );
        verify(span).setStatus(StatusCode.ERROR);
        verify(span).end();
        assertThat(response.getHeader("X-Trace-ID")).isEqualTo(
            SpanContext.getInvalid().getTraceId()
        );
    }

    @Test
    void failedRequestsEndTheirSpanAndTelemetryHealthRemainExcluded()
        throws Exception {
        var telemetry = mock(Telemetry.class);
        var filter = new TracingFilter(telemetry);
        for (String path : new String[] {
            "/actuator/health",
            "/api/observability/metrics",
            "/api/observability/traces",
        }) {
            filter.doFilter(
                new MockHttpServletRequest("GET", path),
                new MockHttpServletResponse(),
                (req, res) -> {}
            );
        }
        verifyNoInteractions(telemetry);
        var span = mock(Span.class);
        when(span.getSpanContext()).thenReturn(SpanContext.getInvalid());
        when(span.makeCurrent()).thenReturn(Scope.noop());
        when(telemetry.incoming(eq("HTTP OTHER /api/other"), any())).thenReturn(
            span
        );
        assertThatThrownBy(() ->
            filter.doFilter(
                new MockHttpServletRequest("SECRET-METHOD", "/api/private-id"),
                new MockHttpServletResponse(),
                (req, res) -> {
                    throw new ServletException("controlled");
                }
            )
        ).isInstanceOf(ServletException.class);
        verify(span).setStatus(StatusCode.ERROR);
        verify(span).end();
    }
}
