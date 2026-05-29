package com.discovery.server.config;

import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Handles graceful shutdown of the Eureka server.
 *
 * <p>When the JVM receives SIGTERM (e.g. from Kubernetes, ECS, or systemd),
 * Spring fires a {@link ContextClosedEvent}. This listener:
 *
 * <ol>
 *   <li>Logs a clear shutdown notice so log aggregators capture the event.</li>
 *   <li>Waits briefly so Kubernetes can remove the pod from the Service endpoints
 *       before connections stop being accepted (avoids 502s during rolling deploys).</li>
 *   <li>Lets the Eureka server context close cleanly, which replicates the shutdown
 *       to peer nodes.</li>
 * </ol>
 *
 * <p>The pre-shutdown sleep is configurable via
 * {@code eureka.server.shutdown.pre-close-delay-ms} (default: 10 seconds).
 * Set to 0 in dev profile.
 *
 * <p>Works together with {@code server.shutdown=graceful} in application-prod.yml,
 * which lets in-flight HTTP requests drain for up to 30 seconds before
 * the Tomcat connector closes.
 */
@Component
public class GracefulShutdownHandler implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final long preCloseDelayMs;

    public GracefulShutdownHandler(
            @org.springframework.beans.factory.annotation.Value(
                    "${eureka.server.shutdown.pre-close-delay-ms:10000}")
            long preCloseDelayMs) {
        this.preCloseDelayMs = preCloseDelayMs;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("SHUTDOWN_INITIATED action=GRACEFUL_SHUTDOWN preCloseDelayMs={}", preCloseDelayMs);

        if (preCloseDelayMs > 0) {
            log.info("SHUTDOWN_WAITING reason=ALLOW_LOAD_BALANCER_DRAIN delayMs={}", preCloseDelayMs);
            try {
                Thread.sleep(preCloseDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("SHUTDOWN_WAIT_INTERRUPTED");
            }
        }

        // Signal peers that this node is going down so they stop replicating to us
        try {
            EurekaServerContext serverContext =
                    EurekaServerContextHolder.getInstance().getServerContext();
            if (serverContext != null) {
                log.info("SHUTDOWN_PEER_NOTIFICATION action=NOTIFYING_PEERS");
                serverContext.getRegistry().shutdown();
                log.info("SHUTDOWN_PEER_NOTIFICATION status=COMPLETE");
            }
        } catch (Exception e) {
            log.warn("SHUTDOWN_PEER_NOTIFICATION status=FAILED error={}", e.getMessage());
        }

        log.info("SHUTDOWN_COMPLETE");
    }
}
