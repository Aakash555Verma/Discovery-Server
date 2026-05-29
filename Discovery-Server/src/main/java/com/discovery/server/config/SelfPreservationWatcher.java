package com.discovery.server.config;

import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled watcher that detects self-preservation mode transitions and
 * emits a clear WARN log entry that ops teams can alert on.
 *
 * <p>Self-preservation mode is Eureka's split-brain protection. When the
 * server stops receiving enough heartbeats (e.g. due to a network partition),
 * it refuses to evict instances so it doesn't accidentally remove healthy
 * services that just can't reach the registry. This is correct behaviour —
 * but ops teams must know about it because stale registrations will persist.
 *
 * <p>Alert rule example (Prometheus / Alertmanager):
 * <pre>
 *   alert: EurekaSelfPreservationActive
 *   expr: eureka_server_self_preservation_active == 1
 *   for: 5m
 *   labels:
 *     severity: warning
 *   annotations:
 *     summary: "Eureka self-preservation mode is active"
 *     description: "Registry may contain stale instances. Check network connectivity."
 * </pre>
 *
 * <p>Disabled in dev profile via {@code eureka.server.self-preservation-watcher.enabled=false}.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
        name = "eureka.server.self-preservation-watcher.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SelfPreservationWatcher {

    private static final Logger log = LoggerFactory.getLogger(SelfPreservationWatcher.class);

    /** Tracks the last known state to only log on transitions, not every tick. */
    private final AtomicBoolean wasSelfPreservationActive = new AtomicBoolean(false);

    /**
     * Runs every 60 seconds. Logs a warning when self-preservation activates
     * or deactivates, and repeats the warning every 5 minutes while active.
     */
    @Scheduled(fixedDelayString = "${eureka.server.self-preservation-watcher.check-interval-ms:60000}")
    public void checkSelfPreservation() {
        try {
            EurekaServerContext ctx = EurekaServerContextHolder.getInstance().getServerContext();
            if (ctx == null) return;

            PeerAwareInstanceRegistry registry = ctx.getRegistry();
            boolean isActive = registry.isSelfPreservationModeEnabled()
                    && !registry.isLeaseExpirationEnabled();

            boolean wasActive = wasSelfPreservationActive.getAndSet(isActive);

            if (isActive && !wasActive) {
                log.warn("SELF_PRESERVATION=ACTIVATED " +
                         "reason=RENEWAL_RATE_BELOW_THRESHOLD " +
                         "action=INSTANCE_EVICTION_SUSPENDED " +
                         "impact=STALE_INSTANCES_MAY_PERSIST " +
                         "recommendation=CHECK_NETWORK_CONNECTIVITY");

            } else if (!isActive && wasActive) {
                log.info("SELF_PRESERVATION=DEACTIVATED " +
                         "reason=RENEWAL_RATE_RESTORED " +
                         "action=INSTANCE_EVICTION_RESUMED");

            } else if (isActive) {
                // Repeat warning every check interval while mode stays active
                log.warn("SELF_PRESERVATION=STILL_ACTIVE " +
                         "action=INSTANCE_EVICTION_STILL_SUSPENDED");
            }

        } catch (Exception e) {
            // Context not yet available during startup — silently ignore
            log.error("Self-preservation watcher: context not ready yet: {}", e.getMessage());
        }
    }
}
