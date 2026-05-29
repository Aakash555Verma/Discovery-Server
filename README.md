# Discovery-Server — Production Ready

A production-grade Spring Boot 3.x Eureka Service Registry with:

- **Basic Auth** protection on all endpoints
- **High Availability** peer-aware replication (2+ nodes)
- **Spring Actuator** with health, info, metrics, prometheus
- **Structured JSON logging** for ELK / CloudWatch / Splunk
- **Kubernetes** StatefulSet manifests with probes & zone spreading
- **Docker** multi-stage build with non-root user
- **GitHub Actions** CI/CD pipeline

---

## Quick Start (Local Dev)

```bash
# 1. Clone & build
./mvnw package -DskipTests

# 2. Run single node (dev profile — no peers needed)
java -jar target/Discovery-Server-1.0.0.jar \
     --spring.profiles.active=dev

# 3. Open dashboard
open http://localhost:8761
# Login: eureka-admin / changeme-in-production
```

---

## Run HA Cluster with Docker Compose

```bash
docker compose up -d

# Node 1: http://localhost:8761
# Node 2: http://localhost:8762
```

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `EUREKA_USERNAME` | `eureka-admin` | Basic Auth username |
| `EUREKA_PASSWORD` | `changeme-in-production` | Basic Auth password |
| `EUREKA_HOSTNAME` | `eureka-1.internal.example.com` | This node's hostname |
| `EUREKA_PEER_URLS` | *(see prod yml)* | Comma-separated peer URLs |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev` or `prod` |
| `AVAILABILITY_ZONE` | `default` | Metadata tag |

---

## How Microservices Connect

Add this to your microservice `application.yml`:

```yaml
spring:
  application:
    name: your-service-name

eureka:
  client:
    service-url:
      defaultZone: http://eureka-admin:${EUREKA_PASSWORD}@eureka-host:8761/eureka/
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10
    lease-expiration-duration-in-seconds: 30
```

And in your microservice `pom.xml`:

```xml
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

---

## Deploy to Kubernetes

```bash
# Update image name in k8s/deployment.yml first
kubectl apply -f k8s/deployment.yml

# Verify
kubectl get pods -n infrastructure
kubectl get svc -n infrastructure
```

---

## Actuator Endpoints

| Endpoint | Auth Required | Purpose |
|---|---|---|
| `/actuator/health` | No | Load balancer / K8s probe |
| `/actuator/info` | No | App version info |
| `/actuator/prometheus` | Yes | Prometheus scraping |
| `/actuator/metrics` | Yes | All metrics |
| `/actuator/loggers` | Yes | Change log levels at runtime |

---

## Production Checklist

- [ ] Set strong `EUREKA_PASSWORD` via secrets manager
- [ ] Replace `eureka-1.internal.example.com` hostnames with real DNS names
- [ ] Update `EUREKA_PEER_URLS` with actual peer node addresses
- [ ] Update Docker image name in `k8s/deployment.yml`
- [ ] Set resource limits appropriate for your traffic volume
- [ ] Configure Prometheus to scrape `/actuator/prometheus`
- [ ] Set up alerts on `eurekaRegistry` health indicator
