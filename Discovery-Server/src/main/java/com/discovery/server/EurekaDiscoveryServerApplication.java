package com.discovery.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Production-Ready Eureka Discovery Server
 *
 * <p>This service acts as the central service registry for all microservices.
 * All microservices register themselves here and discover each other through it.
 *
 * <p>Features:
 * <ul>
 *   <li>Basic Auth protection on dashboard and Eureka API</li>
 *   <li>Actuator endpoints for health, info, metrics</li>
 *   <li>Prometheus metrics endpoint</li>
 *   <li>Peer-aware replication for HA deployments</li>
 *   <li>Profile-based configuration (dev / prod)</li>
 * </ul>
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaDiscoveryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaDiscoveryServerApplication.class, args);
    }
}
