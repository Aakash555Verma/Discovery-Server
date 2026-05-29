package com.discovery.server.actuator;

import com.netflix.discovery.shared.Application;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contributes live registry statistics to the {@code /actuator/info} endpoint.
 *
 * <p>This data is visible without authentication (per SecurityConfig), so it is
 * useful for:
 * <ul>
 *   <li>Load balancer health pages</li>
 *   <li>Service mesh control planes querying registry state</li>
 *   <li>Internal dashboards that don't have admin credentials</li>
 * </ul>
 *
 * <p>Sample response fragment:
 * <pre>
 * {
 *   "registry": {
 *     "registeredApplications": 5,
 *     "totalInstances": 12,
 *     "upInstances": 11,
 *     "selfPreservationActive": false,
 *     "peerCount": 1
 *   }
 * }
 * </pre>
 */
@Component
public class RegistryInfoContributor implements InfoContributor {

    @Override
    public void contribute(Info.Builder builder) {
        try {
            EurekaServerContext ctx = EurekaServerContextHolder.getInstance().getServerContext();
            if (ctx == null) {
                builder.withDetail("registry", Map.of("status", "INITIALIZING"));
                return;
            }

            PeerAwareInstanceRegistry registry = ctx.getRegistry();
            List<Application> apps = registry
                    .getApplicationsFromLocalRegionOnly()
                    .getRegisteredApplications();

            int totalInstances = apps.stream()
                    .mapToInt(a -> a.getInstances().size())
                    .sum();

            long upInstances = apps.stream()
                    .flatMap(a -> a.getInstances().stream())
                    .filter(i -> i.getStatus().name().equals("UP"))
                    .count();

            boolean selfPreservation = registry.isSelfPreservationModeEnabled()
                    && !registry.isLeaseExpirationEnabled();

            int peerCount = ctx.getPeerEurekaNodes().getPeerNodesView().size();

            Map<String, Object> registryInfo = new LinkedHashMap<>();
            registryInfo.put("registeredApplications", apps.size());
            registryInfo.put("totalInstances",         totalInstances);
            registryInfo.put("upInstances",            upInstances);
            registryInfo.put("downInstances",          totalInstances - upInstances);
            registryInfo.put("selfPreservationActive", selfPreservation);
            registryInfo.put("peerCount",              peerCount);

            builder.withDetail("registry", registryInfo);

        } catch (Exception e) {
            builder.withDetail("registry", Map.of("status", "UNAVAILABLE", "error", e.getMessage()));
        }
    }
}
