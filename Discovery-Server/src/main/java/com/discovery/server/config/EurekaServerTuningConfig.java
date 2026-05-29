package com.discovery.server.config;

import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.transport.Jersey3ReplicationClient;
import org.springframework.cloud.netflix.eureka.server.EurekaServerConfigBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fine-grained Eureka server tuning that cannot be set via {@code application.yml}.
 *
 * <p>The {@link EurekaServerConfigBean} is the programmatic equivalent of the
 * {@code eureka.server.*} properties but allows conditional logic and runtime
 * computation that YAML cannot express.
 *
 * <p>Key production tuning applied here:
 * <ul>
 *   <li>Replication thread pool sized to handle the expected number of peers.</li>
 *   <li>Response cache TTL tuned to balance freshness vs CPU cost.</li>
 *   <li>Delta retention window sized for slow consumers (mobile clients, etc.).</li>
 * </ul>
 */
@Configuration
public class EurekaServerTuningConfig {

    /**
     * Customises the Eureka server configuration bean.
     *
     * <p>All values here are the production-recommended defaults. Override them
     * via {@code eureka.server.*} properties in your environment-specific YAML.
     */
    @Bean
    public EurekaServerConfig eurekaServerConfig() {
        EurekaServerConfigBean config = new EurekaServerConfigBean();

        // ---- Response cache ----
        // How long the read-only cache holds a snapshot (ms).
        // Lower = fresher data; Higher = less CPU. 30s is a good balance.
        config.setResponseCacheUpdateIntervalMs(30_000);

        // Disable the read-only cache if you have very few clients and need
        // near-instant propagation (e.g. CI environments). Leave enabled in prod.
        config.setUseReadOnlyResponseCache(true);

        // ---- Delta retention ----
        // How long delta change records are kept for slow clients (ms).
        // 3 minutes is sufficient for clients that fetch every 30 seconds.
        config.setRetentionTimeInMSInDeltaQueue(3 * 60 * 1000);

        // ---- Peer replication ----
        // Max replication threads — scale with number of peer nodes.
        // Rule of thumb: 10 threads per expected peer node.
        config.setMaxThreadsForPeerReplication(20);

        // Max time (ms) to wait for a replication to a peer
        config.setMaxTimeForReplication(30_000);

        // Number of retry attempts when a peer replication fails
        config.setNumberOfReplicationRetries(5);

        // ---- Registry sync on startup ----
        // How long (ms) to wait for peer sync before accepting traffic.
        // Prevents serving a stale registry on a cold start.
        // Set to 0 in dev (application-dev.yml overrides this).
        config.setWaitTimeInMsWhenSyncEmpty(300_000);

        // ---- Renewal threshold ----
        // % of expected heartbeats that must be received before self-preservation kicks in.
        config.setRenewalPercentThreshold(0.85);

        // How often to recompute the threshold (ms). Every 15 min is fine for most loads.
        config.setRenewalThresholdUpdateIntervalMs(15 * 60 * 1000);

        // ---- Eviction ----
        // Eviction task interval (ms). 60s is standard.
        config.setEvictionIntervalTimerInMs(60_000);

        return config;
    }
}
