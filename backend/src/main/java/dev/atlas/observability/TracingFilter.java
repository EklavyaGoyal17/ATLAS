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
        // Static route families only: never record user paths, queries, bodies or credentials.
        String family = java.util.Set.of(
            "payments",
            "events",
            "worker",
            "experiments",
            "ledger",
            "workspace",
            "repository",
            "assistant",
            "simulations",
            "webhooks"
        )
            .stream()
            .filter(x -> req.getRequestURI().startsWith("/api/" + x))
            .findFirst()
            .orElse("other");
        var span = telemetry.incoming(
            "HTTP " + req.getMethod() + " /api/" + family,
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
