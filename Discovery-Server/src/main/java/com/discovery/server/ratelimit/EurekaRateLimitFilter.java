package com.discovery.server.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP rate limiter for the Eureka REST API.
 *
 * <p>In production, microservices retry aggressively on startup — this filter
 * prevents a single misbehaving service from flooding the registry and starving
 * legitimate heartbeats.
 *
 * <p>Uses a sliding-window counter per client IP. Resets every {@code windowSeconds}.
 * Returns HTTP 429 with a Retry-After header when a client exceeds the limit.
 *
 * <p>Only applies to {@code /eureka/**} paths. Dashboard and actuator endpoints
 * are excluded.
 */
@Component
@Order(1)
public class EurekaRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EurekaRateLimitFilter.class);

    /** Max requests per client IP per window. */
    @Value("${eureka.server.rate-limit.max-requests-per-window:200}")
    private int maxRequestsPerWindow;

    /** Window duration in seconds. */
    @Value("${eureka.server.rate-limit.window-seconds:60}")
    private int windowSeconds;

    /** Whether rate limiting is active. Can be toggled at runtime via /actuator/env. */
    @Value("${eureka.server.rate-limit.enabled:true}")
    private boolean enabled;

    private record WindowCounter(AtomicInteger count, Instant windowStart) {}

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only rate-limit the Eureka REST API — not the dashboard or actuator
        String path = request.getRequestURI();
        return !path.startsWith("/eureka/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        WindowCounter counter = counters.compute(clientIp, (ip, existing) -> {
            Instant now = Instant.now();
            if (existing == null ||
                    existing.windowStart().plusSeconds(windowSeconds).isBefore(now)) {
                return new WindowCounter(new AtomicInteger(1), now);
            }
            existing.count().incrementAndGet();
            return existing;
        });

        int currentCount = counter.count().get();

        if (currentCount > maxRequestsPerWindow) {
            log.warn("Rate limit exceeded for IP={} count={} limit={} path={}",
                    clientIp, currentCount, maxRequestsPerWindow, request.getRequestURI());

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(windowSeconds));
            response.setHeader("X-RateLimit-Limit", String.valueOf(maxRequestsPerWindow));
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error":"Too Many Requests","message":"Rate limit exceeded. Retry after %d seconds."}
                    """.formatted(windowSeconds));
            return;
        }

        // Propagate remaining quota to the client
        response.setHeader("X-RateLimit-Limit", String.valueOf(maxRequestsPerWindow));
        response.setHeader("X-RateLimit-Remaining",
                String.valueOf(Math.max(0, maxRequestsPerWindow - currentCount)));

        filterChain.doFilter(request, response);
    }

    /**
     * Resolves the real client IP, honouring reverse proxy headers.
     * Order: X-Forwarded-For → X-Real-IP → remoteAddr
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();  // First entry = original client
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
