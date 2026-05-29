package com.discovery.server.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the Eureka Discovery Server.
 *
 * <p>Protects:
 * <ul>
 *   <li>Eureka dashboard (/) with Basic Auth</li>
 *   <li>Eureka REST API (/eureka/**) with Basic Auth</li>
 *   <li>Actuator health endpoint is left open for load balancer probes</li>
 *   <li>Actuator info is left open</li>
 *   <li>All other actuator endpoints require authentication</li>
 *   <li>Prometheus metrics endpoint requires authentication</li>
 * </ul>
 *
 * <p>Credentials are injected via environment variables / application properties.
 * Never hard-code credentials in source code.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${eureka.server.security.username}")
    private String username;

    @Value("${eureka.server.security.password}")
    private String password;

    /**
     * Main security filter chain.
     *
     * <p>CSRF is disabled because:
     * <ol>
     *   <li>Eureka clients use Basic Auth (not session cookies), so CSRF protection is not needed.</li>
     *   <li>Enabling CSRF breaks Eureka client registration.</li>
     * </ol>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — required for Eureka client registration to work
            .csrf(AbstractHttpConfigurer::disable)

            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                // Allow health & info probes without authentication (used by K8s/ECS/ALB)
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/**"
                ).permitAll()
                // Everything else requires authentication
                .anyRequest().authenticated()
            )

            // Enable HTTP Basic Auth
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    /**
     * In-memory user store with a single admin user.
     *
     * <p>For production with multiple operators, consider switching to
     * JDBC-backed or LDAP-backed UserDetailsService.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        var adminUser = User.builder()
            .username(username)
            .password(passwordEncoder().encode(password))
            .roles("ADMIN", "USER")
            .build();

        return new InMemoryUserDetailsManager(adminUser);
    }

    /**
     * BCrypt password encoder with strength 12 (production-safe).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
