package dev.atlas.observability;

import io.opentelemetry.api.trace.StatusCode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TracingFilter extends OncePerRequestFilter {

    private final Telemetry telemetry;

    public TracingFilter(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return (
            !r.getRequestURI().startsWith("/api/") ||
            r.getRequestURI().startsWith("/api/observability")
        );
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest req,
        HttpServletResponse res,
        FilterChain chain
    ) throws ServletException, IOException {
        var span = telemetry.incoming(
            HttpOperations.label(req.getMethod(), req.getRequestURI()),
            req
        );
        res.setHeader("X-Trace-ID", span.getSpanContext().getTraceId());
        try (var scope = span.makeCurrent()) {
            chain.doFilter(req, res);
        } catch (ServletException | IOException | RuntimeException e) {
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            if (res.getStatus() >= 400) span.setStatus(StatusCode.ERROR);
            span.end();
        }
    }
}
