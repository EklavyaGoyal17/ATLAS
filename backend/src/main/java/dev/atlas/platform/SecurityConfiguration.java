package dev.atlas.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(
        SecurityConfiguration.class
    );

    @Bean
    SecurityFilterChain security(
        HttpSecurity http,
        RequestBudget budget,
        @Value("${atlas.api-token}") String token
    ) throws Exception {
        if (token.length() < 32) throw new IllegalStateException(
            "Set ATLAS_API_TOKEN to at least 32 characters"
        );
        return http
            .csrf(csrf -> csrf.disable()) // Bearer header only; no cookie or browser-managed authentication.
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth ->
                auth
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            )
            .exceptionHandling(errors ->
                errors.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write(
                        "{\"code\":\"UNAUTHORIZED\",\"message\":\"A valid ATLAS bearer token is required\"}"
                    );
                })
            )
            .addFilterBefore(
                new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(
                        HttpServletRequest req,
                        HttpServletResponse res,
                        FilterChain chain
                    ) throws ServletException, IOException {
                        UUID correlation = UUID.randomUUID();
                        MDC.put("correlationId", correlation.toString());
                        res.setHeader(
                            "X-Correlation-ID",
                            correlation.toString()
                        );
                        String supplied = req.getHeader("Authorization");
                        if (
                            supplied != null &&
                            MessageDigest.isEqual(
                                supplied.getBytes(StandardCharsets.UTF_8),
                                ("Bearer " + token).getBytes(
                                    StandardCharsets.UTF_8
                                )
                            )
                        ) {
                            SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                    "local-engineer",
                                    null,
                                    List.of(
                                        new SimpleGrantedAuthority(
                                            "ROLE_ENGINEER"
                                        )
                                    )
                                )
                            );
                        }
                        long start = System.nanoTime();
                        try {
                            if (
                                SecurityContextHolder.getContext().getAuthentication() !=
                                    null &&
                                !budget.allow()
                            ) {
                                res.setStatus(429);
                                res.setHeader("Retry-After", "1");
                                res.setContentType("application/json");
                                res.getWriter().write(
                                    "{\"code\":\"RATE_LIMITED\",\"message\":\"Local request budget exhausted. Retry after one second.\"}"
                                );
                                return;
                            }
                            if (
                                SecurityContextHolder.getContext().getAuthentication() !=
                                    null &&
                                java.util.Set.of(
                                    "POST",
                                    "PUT",
                                    "PATCH"
                                ).contains(req.getMethod())
                            ) {
                                BoundedRequest bounded = new BoundedRequest(
                                    req
                                );
                                if (bounded.tooLarge()) {
                                    res.setStatus(413);
                                    res.setContentType("application/json");
                                    res.getWriter().write(
                                        "{\"code\":\"REQUEST_TOO_LARGE\",\"message\":\"JSON body limit is 16 KiB\"}"
                                    );
                                    return;
                                }
                                chain.doFilter(bounded, res);
                            } else {
                                chain.doFilter(req, res);
                            }
                        } finally {
                            log.atInfo()
                                .addKeyValue("method", req.getMethod())
                                .addKeyValue("status", res.getStatus())
                                .addKeyValue(
                                    "durationMs",
                                    (System.nanoTime() - start) / 1_000_000
                                )
                                .log("http_request"); // Never log headers, bodies, credentials, or arbitrary user paths.
                            MDC.clear();
                        }
                    }
                },
                UsernamePasswordAuthenticationFilter.class
            )
            .build();
    }
}
