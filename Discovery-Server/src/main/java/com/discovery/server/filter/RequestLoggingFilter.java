package com.discovery.server.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Structured access log filter and MDC (Mapped Diagnostic Context) populator.
 *
 * <p>Every request gets a unique {@code requestId} injected into the MDC so that
 * all log lines emitted during that request can be correlated in your log aggregator.
 *
 * <p>This is critical in production for tracing a slow Eureka registration through
 * multiple log lines without having to grep by timestamp ranges.
 *
 * <p>Log format (one line per request):
 * <pre>
 *   ACCESS method=POST path=/eureka/apps/MY-SERVICE status=204 durationMs=12
 *          clientIp=10.0.1.5 requestId=3f2a1b4c user=eureka-admin
 * </pre>
 */
@Component
@Order(2)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("ACCESS_LOG");

    private static final List<String> TRACE_HEADERS = List.of(
            "X-Request-Id", "X-Correlation-Id", "X-Amzn-Trace-Id", "traceparent");

    public static final String MDC_REQUEST_ID  = "requestId";
    public static final String MDC_CLIENT_IP   = "clientIp";
    public static final String MDC_HTTP_METHOD = "method";
    public static final String MDC_PATH        = "path";
    public static final String MDC_USER        = "user";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        long   startMs   = System.currentTimeMillis();
        String requestId = resolveRequestId(request);
        String clientIp  = resolveClientIp(request);
        String user      = request.getUserPrincipal() != null
                ? request.getUserPrincipal().getName() : "anonymous";

        MDC.put(MDC_REQUEST_ID,  requestId);
        MDC.put(MDC_CLIENT_IP,   clientIp);
        MDC.put(MDC_HTTP_METHOD, request.getMethod());
        MDC.put(MDC_PATH,        request.getRequestURI());
        MDC.put(MDC_USER,        user);

        response.setHeader("X-Request-Id", requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            long    durationMs  = System.currentTimeMillis() - startMs;
            int     status      = response.getStatus();
            boolean isHeartbeat = request.getRequestURI().contains("/eureka/apps/")
                    && "PUT".equals(request.getMethod());

            if (!isHeartbeat || log.isDebugEnabled()) {
                log.info("ACCESS method={} path={} status={} durationMs={} clientIp={} requestId={} user={}",
                        request.getMethod(), request.getRequestURI(), status,
                        durationMs, clientIp, requestId, user);
            }
            MDC.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        for (String header : TRACE_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) return value;
        }
        return UUID.randomUUID().toString();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }
}
