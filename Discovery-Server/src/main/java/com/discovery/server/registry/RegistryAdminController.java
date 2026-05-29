package com.discovery.server.registry;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Application;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Custom REST API for querying the registry in a production-friendly way.
 *
 * <p>The built-in Eureka REST API returns raw XML/JSON that is verbose and hard
 * to parse for ops tooling. This controller exposes clean, structured JSON
 * endpoints for dashboards, runbooks, and automated health checks.
 *
 * <p>All endpoints are protected by Basic Auth (via {@code SecurityConfig}).
 *
 * <p>Base path: {@code /admin/registry}
 */
@RestController
@RequestMapping("/admin/registry")
public class RegistryAdminController {

    // ---------------------------------------------------------------
    // GET /admin/registry/summary
    // ---------------------------------------------------------------

    /**
     * Returns a high-level summary of the registry state.
     * Use this as the first call in any runbook / incident response.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        PeerAwareInstanceRegistry registry = getRegistry();

        List<Application> apps = registry.getApplicationsFromLocalRegionOnly()
                .getRegisteredApplications();

        int totalInstances = apps.stream()
                .mapToInt(a -> a.getInstances().size())
                .sum();

        long upCount = apps.stream()
                .flatMap(a -> a.getInstances().stream())
                .filter(i -> i.getStatus() == InstanceInfo.InstanceStatus.UP)
                .count();

        long downCount = totalInstances - upCount;

        boolean selfPreservation = registry.isSelfPreservationModeEnabled()
                && !registry.isLeaseExpirationEnabled();

        int peerCount = EurekaServerContextHolder.getInstance()
                .getServerContext()
                .getPeerEurekaNodes()
                .getPeerNodesView()
                .size();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("registeredApplications", apps.size());
        body.put("totalInstances", totalInstances);
        body.put("upInstances", upCount);
        body.put("downInstances", downCount);
        body.put("selfPreservationActive", selfPreservation);
        body.put("peerCount", peerCount);

        return ResponseEntity.ok(body);
    }

    // ---------------------------------------------------------------
    // GET /admin/registry/applications
    // ---------------------------------------------------------------

    /**
     * Lists all registered applications with per-instance details.
     * Useful for verifying that all expected services are registered.
     */
    @GetMapping("/applications")
    public ResponseEntity<List<Map<String, Object>>> applications() {
        List<Application> apps = getRegistry()
                .getApplicationsFromLocalRegionOnly()
                .getRegisteredApplications();

        List<Map<String, Object>> result = apps.stream()
                .sorted(Comparator.comparing(Application::getName))
                .map(app -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", app.getName());
                    entry.put("instanceCount", app.getInstances().size());
                    entry.put("instances", app.getInstances().stream()
                            .map(this::toInstanceMap)
                            .collect(Collectors.toList()));
                    return entry;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ---------------------------------------------------------------
    // GET /admin/registry/applications/{appName}
    // ---------------------------------------------------------------

    /**
     * Returns all instances of a specific application.
     * Handy when you need to verify a specific service's registration state.
     */
    @GetMapping("/applications/{appName}")
    public ResponseEntity<?> application(@PathVariable String appName) {
        Application app = getRegistry().getApplication(appName.toUpperCase());

        if (app == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", app.getName());
        body.put("instanceCount", app.getInstances().size());
        body.put("instances", app.getInstances().stream()
                .map(this::toInstanceMap)
                .collect(Collectors.toList()));

        return ResponseEntity.ok(body);
    }

    // ---------------------------------------------------------------
    // GET /admin/registry/instances/status/{status}
    // ---------------------------------------------------------------

    /**
     * Filters instances by status: UP, DOWN, STARTING, OUT_OF_SERVICE, UNKNOWN.
     * Very useful for finding unhealthy instances across all services at once.
     */
    @GetMapping("/instances/status/{status}")
    public ResponseEntity<?> instancesByStatus(@PathVariable String status) {
        InstanceInfo.InstanceStatus targetStatus;
        try {
            targetStatus = InstanceInfo.InstanceStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid status. Valid values: " +
                            Arrays.toString(InstanceInfo.InstanceStatus.values())));
        }

        List<Map<String, Object>> instances = getRegistry()
                .getApplicationsFromLocalRegionOnly()
                .getRegisteredApplications()
                .stream()
                .flatMap(app -> app.getInstances().stream())
                .filter(i -> i.getStatus() == targetStatus)
                .map(this::toInstanceMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "status", status.toUpperCase(),
                "count", instances.size(),
                "instances", instances
        ));
    }

    // ---------------------------------------------------------------
    // DELETE /admin/registry/applications/{appName}/{instanceId}
    // ---------------------------------------------------------------

    /**
     * Manually evicts a stuck instance from the registry.
     *
     * <p>This is needed in production when:
     * <ul>
     *   <li>A JVM crashed without sending a deregister (firewall blocked it)</li>
     *   <li>A container was force-killed by the orchestrator</li>
     *   <li>Self-preservation mode is preventing automatic eviction</li>
     * </ul>
     *
     * <p>Use with caution — this does NOT stop the actual instance, only removes
     * it from the registry. Other services will stop routing to it immediately.
     */
    @DeleteMapping("/applications/{appName}/{instanceId}")
    public ResponseEntity<Map<String, Object>> evictInstance(
            @PathVariable String appName,
            @PathVariable String instanceId) {

        boolean cancelled = getRegistry().cancel(appName.toUpperCase(), instanceId, false);

        if (!cancelled) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "action", "EVICTED",
                "appName", appName.toUpperCase(),
                "instanceId", instanceId,
                "timestamp", Instant.now().toString()
        ));
    }

    // ---------------------------------------------------------------
    // PUT /admin/registry/applications/{appName}/{instanceId}/status
    // ---------------------------------------------------------------

    /**
     * Overrides the status of a registered instance.
     *
     * <p>Use cases:
     * <ul>
     *   <li>Mark an instance OUT_OF_SERVICE before a deployment to drain traffic</li>
     *   <li>Bring an instance back UP after verifying it is healthy</li>
     * </ul>
     *
     * <p>Request body: {@code { "status": "OUT_OF_SERVICE" }}
     */
    @PutMapping("/applications/{appName}/{instanceId}/status")
    public ResponseEntity<?> overrideStatus(
            @PathVariable String appName,
            @PathVariable String instanceId,
            @RequestBody Map<String, String> body) {

        String statusStr = body.get("status");
        if (statusStr == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Request body must contain 'status' field"));
        }

        InstanceInfo.InstanceStatus newStatus;
        try {
            newStatus = InstanceInfo.InstanceStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid status: " + statusStr));
        }

        // FIX: AbstractInstanceRegistry.statusUpdate() requires 5 arguments:
        //   statusUpdate(String appName, String id, InstanceStatus newStatus,
        //                String lastDirtyTimestamp, boolean isReplication)
        // The 4-argument overload does NOT exist on AbstractInstanceRegistry.
        // Pass current timestamp as lastDirtyTimestamp (same as the Eureka REST resource does).
        boolean updated = getRegistry().statusUpdate(
                appName.toUpperCase(), instanceId, newStatus,
                String.valueOf(System.currentTimeMillis()),  // lastDirtyTimestamp
                false);                                      // isReplication

        if (!updated) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "action", "STATUS_OVERRIDE",
                "appName", appName.toUpperCase(),
                "instanceId", instanceId,
                "newStatus", newStatus.name(),
                "timestamp", Instant.now().toString()
        ));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private PeerAwareInstanceRegistry getRegistry() {
        return EurekaServerContextHolder.getInstance()
                .getServerContext()
                .getRegistry();
    }

    private Map<String, Object> toInstanceMap(InstanceInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId",   info.getInstanceId());
        m.put("hostName",     info.getHostName());
        m.put("ipAddr",       info.getIPAddr());
        m.put("port",         info.getPort());
        m.put("securePort",   info.getSecurePort());
        m.put("status",       info.getStatus().name());
        m.put("overriddenStatus", info.getOverriddenStatus().name());
        m.put("appName",      info.getAppName());
        m.put("vipAddress",   info.getVIPAddress());
        m.put("homePageUrl",  info.getHomePageUrl());
        m.put("healthCheckUrl", info.getHealthCheckUrl());
        m.put("lastUpdatedTimestamp", Instant.ofEpochMilli(info.getLastUpdatedTimestamp()).toString());
        m.put("lastDirtyTimestamp",   Instant.ofEpochMilli(info.getLastDirtyTimestamp()).toString());
        m.put("metadata",     info.getMetadata());
        return m;
    }
}
