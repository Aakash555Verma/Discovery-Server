package com.discovery.server.metrics;

import com.discovery.server.audit.EurekaAuditEventListener;
import com.netflix.discovery.shared.Application;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registers custom Micrometer gauges for the Eureka registry.
 *
 * <p>All metrics are prefixed with {@code eureka.server.*} for easy discovery
 * in Prometheus / Grafana.
 *
 * <p>Metrics exposed:
 * <pre>
 *   eureka_server_registered_instances      — live instance count
 *   eureka_server_registered_applications   — registered app count
 *   eureka_server_peer_count                — connected peer nodes
 *   eureka_server_self_preservation_active  — 1 if in self-preservation mode
 *   eureka_server_events_registrations      — cumulative registrations since startup
 *   eureka_server_events_deregistrations    — cumulative deregistrations since startup
 *   eureka_server_events_renewals           — cumulative heartbeats since startup
 *   eureka_server_events_expirations        — cumulative expirations since startup
 * </pre>
 *
 * <p>FIX: Gauge.builder(name, stateObject, ToDoubleFunction) requires the
 * ToDoubleFunction to be accessible. Using a private method reference like
 * {@code RegistryEventMetrics::countInstances} fails at runtime because the
 * method reference target is inaccessible to the Micrometer internals. The
 * correct approach is to use inline lambdas — they close over {@code this} and
 * have no visibility restrictions.
 */
@Component
public class RegistryEventMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(RegistryEventMetrics.class);

    private final EurekaAuditEventListener auditListener;

    public RegistryEventMetrics(EurekaAuditEventListener auditListener) {
        this.auditListener = auditListener;
    }

    @Override
    public void bindTo(MeterRegistry registry) {

        // ---- Live registry gauges (polled on every Prometheus scrape) ----
        // FIX: use inline lambdas instead of private method references.
        //      Gauge.builder's ToDoubleFunction must be accessible; private
        //      method refs cause IllegalAccessException at instrumentation time.

        Gauge.builder("eureka.server.registered.instances",
                        this, m -> m.countInstances())
                .description("Total number of registered service instances")
                .register(registry);

        Gauge.builder("eureka.server.registered.applications",
                        this, m -> m.countApplications())
                .description("Total number of registered service applications")
                .register(registry);

        Gauge.builder("eureka.server.peer.count",
                        this, m -> m.countPeers())
                .description("Number of connected Eureka peer nodes")
                .register(registry);

        Gauge.builder("eureka.server.self.preservation.active",
                        this, m -> m.selfPreservationActive())
                .description("1 if self-preservation mode is active, 0 otherwise")
                .register(registry);

        // ---- Cumulative event counters from the audit listener ----
        // These use public getters on EurekaAuditEventListener — no access issue.

        Gauge.builder("eureka.server.events.registrations",
                        auditListener, l -> (double) l.getRegistrationCount())
                .description("Cumulative service instance registrations since startup")
                .register(registry);

        Gauge.builder("eureka.server.events.deregistrations",
                        auditListener, l -> (double) l.getDeregistrationCount())
                .description("Cumulative service instance deregistrations since startup")
                .register(registry);

        Gauge.builder("eureka.server.events.renewals",
                        auditListener, l -> (double) l.getRenewalCount())
                .description("Cumulative heartbeat renewals received since startup")
                .register(registry);

        Gauge.builder("eureka.server.events.expirations",
                        auditListener, l -> (double) l.getExpirationCount())
                .description("Cumulative instance expirations (missed heartbeats) since startup")
                .register(registry);
    }

    // ---------------------------------------------------------------
    // Package-private helpers — called via lambda above.
    // Must NOT be private; lambdas in bindTo() close over 'this' but
    // the bytecode Micrometer generates still needs accessible targets.
    // ---------------------------------------------------------------

    double countInstances() {
        return safeRegistryValue(() -> {
            List<Application> apps = getRegistry()
                    .getApplicationsFromLocalRegionOnly()
                    .getRegisteredApplications();
            return (double) apps.stream().mapToInt(a -> a.getInstances().size()).sum();
        });
    }

    double countApplications() {
        return safeRegistryValue(() ->
                (double) getRegistry()
                        .getApplicationsFromLocalRegionOnly()
                        .getRegisteredApplications().size());
    }

    double countPeers() {
        return safeRegistryValue(() ->
                (double) EurekaServerContextHolder.getInstance()
                        .getServerContext()
                        .getPeerEurekaNodes()
                        .getPeerNodesView()
                        .size());
    }

    double selfPreservationActive() {
        return safeRegistryValue(() -> {
            PeerAwareInstanceRegistry reg = getRegistry();
            boolean active = reg.isSelfPreservationModeEnabled()
                    && !reg.isLeaseExpirationEnabled();
            return active ? 1.0 : 0.0;
        });
    }

    private PeerAwareInstanceRegistry getRegistry() {
        EurekaServerContext ctx = EurekaServerContextHolder.getInstance().getServerContext();
        return ctx.getRegistry();
    }

    /** Swallows exceptions during the warm-up period before Eureka context is ready. */
    private double safeRegistryValue(java.util.concurrent.Callable<Double> supplier) {
        try {
            return supplier.call();
        } catch (Exception e) {
            log.debug("Registry not yet available for metrics: {}", e.getMessage());
            return 0.0;
        }
    }
}
