package com.discovery.server.health;

import com.netflix.discovery.shared.Applications;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator that reports Eureka registry statistics.
 *
 * <p>Exposed at: GET /actuator/health/eurekaRegistry
 *
 * <p>Reports:
 * <ul>
 *   <li>Total registered instances (across all apps in local region)</li>
 *   <li>Total registered applications</li>
 *   <li>Whether the registry is in self-preservation mode</li>
 *   <li>Peer replica count</li>
 * </ul>
 */
@Component("eurekaRegistry")
public class EurekaRegistryHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        try {
            EurekaServerContext serverContext = EurekaServerContextHolder.getInstance().getServerContext();
            PeerAwareInstanceRegistry registry = serverContext.getRegistry();

            // getApplicationsFromLocalRegionOnly() returns Applications (not a List).
            // Stream over each Application and sum up instance counts.
            Applications localApps = registry.getApplicationsFromLocalRegionOnly();
            int registeredApps = localApps.getRegisteredApplications().size();
            int registeredInstances = localApps.getRegisteredApplications()
                    .stream()
                    .mapToInt(app -> app.getInstances().size())
                    .sum();

            // Self-preservation is active when the mode is enabled AND lease
            // expiration has been suspended (i.e. the renewal rate dropped too low).
            boolean selfPreservation = registry.isSelfPreservationModeEnabled()
                    && !registry.isLeaseExpirationEnabled();

            // Number of peer Eureka nodes this server is replicating to/from.
            int peerCount = serverContext.getPeerEurekaNodes().getPeerNodesView().size();

            Health.Builder builder = selfPreservation
                    ? Health.status("SELF_PRESERVATION")   // Non-UP, but not DOWN — warns operators
                    : Health.up();

            return builder
                    .withDetail("registeredApplications", registeredApps)
                    .withDetail("registeredInstances", registeredInstances)
                    .withDetail("selfPreservationMode", selfPreservation)
                    .withDetail("peerCount", peerCount)
                    .build();

        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
