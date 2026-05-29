package com.discovery.server.audit;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.registry.AbstractInstanceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.netflix.eureka.server.event.*;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Listens to all Eureka lifecycle events and emits structured audit log entries.
 *
 * <p><b>Why EurekaInstanceExpiredEvent does NOT exist in Spring Cloud Netflix 4.x:</b><br>
 * The Spring Cloud Netflix {@code InstanceRegistry} class only publishes three
 * application events:
 * <ul>
 *   <li>{@link EurekaInstanceRegisteredEvent} — on register()</li>
 *   <li>{@link EurekaInstanceCanceledEvent}   — on cancel() / internalCancel()</li>
 *   <li>{@link EurekaInstanceRenewedEvent}    — on renew()</li>
 * </ul>
 *
 * Instance expiry (when a lease times out due to missed heartbeats) is handled
 * entirely inside the Netflix {@code AbstractInstanceRegistry.evict()} method,
 * which runs on a background timer. Spring Cloud Netflix never added a Spring
 * event for this — so {@code EurekaInstanceExpiredEvent} simply does not exist
 * in the {@code org.springframework.cloud.netflix.eureka.server.event} package.
 *
 * <p><b>Production alternative — expiry detection via lease inspection:</b><br>
 * This class implements a scheduled expiry watcher that compares the last-known
 * set of UP instances against the current registry state, detects any that
 * disappeared without a clean {@link EurekaInstanceCanceledEvent}, and emits
 * the same structured WARN log that you would have gotten from the missing event.
 * This is exactly how production teams handle this in Spring Cloud Netflix 4.x.
 *
 * <p>Counters are maintained for Micrometer/Prometheus export via
 * {@link com.discovery.server.metrics.RegistryEventMetrics}.
 */
@Component
@EnableScheduling
public class EurekaAuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(EurekaAuditEventListener.class);

    // Cumulative event counters — exported to Prometheus
    private final AtomicLong registrationCount   = new AtomicLong();
    private final AtomicLong deregistrationCount = new AtomicLong();
    private final AtomicLong renewalCount        = new AtomicLong();
    private final AtomicLong expirationCount     = new AtomicLong();

    /**
     * Snapshot of instanceId → appName for all instances known to be UP.
     * Used by the expiry watcher to detect disappeared instances.
     */
    private final ConcurrentHashMap<String, String> knownInstances = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------

    @EventListener
    public void onInstanceRegistered(EurekaInstanceRegisteredEvent event) {
        registrationCount.incrementAndGet();
        InstanceInfo info = event.getInstanceInfo();

        // Track this instance so the expiry watcher can detect when it disappears
        knownInstances.put(info.getInstanceId(), info.getAppName());

        log.info("event=REGISTERED app={} instanceId={} host={} port={} status={} isReplication={} ts={}",
                info.getAppName(),
                info.getInstanceId(),
                info.getHostName(),
                info.getPort(),
                info.getStatus(),
                event.isReplication(),
                Instant.now());
    }

    // ---------------------------------------------------------------
    // Deregistration (clean shutdown — client called DELETE /eureka/apps)
    // ---------------------------------------------------------------

    @EventListener
    public void onInstanceCanceled(EurekaInstanceCanceledEvent event) {
        deregistrationCount.incrementAndGet();

        // Remove from tracking so expiry watcher does not double-count it
        knownInstances.remove(event.getServerId());

        log.info("event=DEREGISTERED app={} serverId={} isReplication={} ts={}",
                event.getAppName(),
                event.getServerId(),
                event.isReplication(),
                Instant.now());
    }

    // ---------------------------------------------------------------
    // Heartbeat / Renewal
    // ---------------------------------------------------------------

    @EventListener
    public void onInstanceRenewed(EurekaInstanceRenewedEvent event) {
        renewalCount.incrementAndGet();
        // Heartbeats are very frequent — use TRACE to avoid flooding logs.
        // Enable with: logging.level.com.discovery.server.audit=TRACE
        if (log.isTraceEnabled()) {
            log.trace("event=HEARTBEAT app={} serverId={} isReplication={} ts={}",
                    event.getAppName(),
                    event.getServerId(),
                    event.isReplication(),
                    Instant.now());
        }
    }

    // ---------------------------------------------------------------
    // Registry ready
    // ---------------------------------------------------------------

    @EventListener
    public void onRegistryAvailable(EurekaRegistryAvailableEvent event) {
        log.info("event=REGISTRY_AVAILABLE ts={}", Instant.now());
    }

    // ---------------------------------------------------------------
    // Server ready (peers synced, traffic accepted)
    // ---------------------------------------------------------------

    @EventListener
    public void onServerStarted(EurekaServerStartedEvent event) {
        log.info("event=SERVER_STARTED ts={} action=ACCEPTING_TRAFFIC", Instant.now());
    }

    // ---------------------------------------------------------------
    // Expiry detection — runs every 30 seconds.
    //
    // EurekaInstanceExpiredEvent does NOT exist in Spring Cloud Netflix 4.x.
    // Expiry is handled inside AbstractInstanceRegistry.evict() with no
    // corresponding Spring application event. We detect it by comparing our
    // snapshot of known instances against the live registry.
    //
    // An instance is considered EXPIRED (not just deregistered) when it
    // disappears from the registry WITHOUT us having seen an
    // EurekaInstanceCanceledEvent for it first.
    // ---------------------------------------------------------------

    @Scheduled(fixedDelayString = "${eureka.server.expiry-watcher.interval-ms:30000}")
    public void detectExpiredInstances() {
        try {
            EurekaServerContext ctx = EurekaServerContextHolder.getInstance().getServerContext();
            if (ctx == null || knownInstances.isEmpty()) return;

            // Build the set of instanceIds currently in the live registry
            java.util.Set<String> liveInstanceIds = ctx.getRegistry()
                    .getApplicationsFromLocalRegionOnly()
                    .getRegisteredApplications()
                    .stream()
                    .flatMap(app -> app.getInstances().stream())
                    .map(InstanceInfo::getInstanceId)
                    .collect(java.util.stream.Collectors.toSet());

            // Any instance in our snapshot that is NOT in the live registry
            // disappeared without a clean deregister — i.e. it expired / was evicted.
            for (Map.Entry<String, String> entry : knownInstances.entrySet()) {
                String instanceId = entry.getKey();
                String appName    = entry.getValue();

                if (!liveInstanceIds.contains(instanceId)) {
                    expirationCount.incrementAndGet();
                    knownInstances.remove(instanceId);

                    // Same severity and field names as EurekaInstanceExpiredEvent would have had
                    log.warn("event=EXPIRED app={} instanceId={} ts={} action=INSTANCE_EVICTED " +
                             "reason=MISSED_HEARTBEATS",
                            appName, instanceId, Instant.now());
                }
            }

        } catch (Exception e) {
            log.debug("Expiry watcher: registry not yet ready: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Accessors for Prometheus export
    // ---------------------------------------------------------------

    public long getRegistrationCount()   { return registrationCount.get(); }
    public long getDeregistrationCount() { return deregistrationCount.get(); }
    public long getRenewalCount()        { return renewalCount.get(); }
    public long getExpirationCount()     { return expirationCount.get(); }
}
