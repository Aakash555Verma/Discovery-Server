package com.discovery.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class EurekaDiscoveryServerApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    // ---------------------------------------------------------------
    // Context
    // ---------------------------------------------------------------

    @Test
    void contextLoads() {}

    // ---------------------------------------------------------------
    // Security — unauthenticated
    // ---------------------------------------------------------------

    @Test
    void givenNoAuth_whenAccessDashboard_thenUnauthorized() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isUnauthorized());
    }

    @Test
    void givenNoAuth_whenAccessEurekaApi_thenUnauthorized() throws Exception {
        mockMvc.perform(get("/eureka/apps")).andExpect(status().isUnauthorized());
    }

    @Test
    void givenNoAuth_whenAccessAdminRegistry_thenUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/registry/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void givenNoAuth_whenAccessActuatorMetrics_thenUnauthorized() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------
    // Actuator — open endpoints (no auth required for probes)
    // ---------------------------------------------------------------

    @Test
    void givenNoAuth_whenAccessHealthEndpoint_thenOk() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void givenNoAuth_whenAccessInfoEndpoint_thenOk() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------
    // Authenticated access
    // ---------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdminUser_whenAccessEurekaApps_thenOk() throws Exception {
        mockMvc.perform(get("/eureka/apps")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdminUser_whenAccessPrometheus_thenOk() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------
    // Admin Registry API
    // ---------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdminUser_whenAccessRegistrySummary_thenOkWithExpectedFields() throws Exception {
        mockMvc.perform(get("/admin/registry/summary")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredApplications").exists())
                .andExpect(jsonPath("$.totalInstances").exists())
                .andExpect(jsonPath("$.upInstances").exists())
                .andExpect(jsonPath("$.selfPreservationActive").exists())
                .andExpect(jsonPath("$.peerCount").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdminUser_whenAccessRegistryApplications_thenOkWithList() throws Exception {
        mockMvc.perform(get("/admin/registry/applications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdminUser_whenAccessNonExistentApp_thenNotFound() throws Exception {
        mockMvc.perform(get("/admin/registry/applications/NON-EXISTENT-SERVICE")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdminUser_whenFilterInstancesByStatus_thenOk() throws Exception {
        mockMvc.perform(get("/admin/registry/instances/status/UP")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.count").exists())
                .andExpect(jsonPath("$.instances").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdminUser_whenFilterInstancesByInvalidStatus_thenBadRequest() throws Exception {
        mockMvc.perform(get("/admin/registry/instances/status/INVALID_STATUS")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------
    // Rate Limiting (X-RateLimit headers)
    // ---------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAuthenticatedRequest_whenCallEurekaApi_thenRateLimitHeadersPresent() throws Exception {
        mockMvc.perform(get("/eureka/apps"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    // ---------------------------------------------------------------
    // Request ID propagation
    // ---------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenRequest_whenCallAnyEndpoint_thenRequestIdHeaderInResponse() throws Exception {
        mockMvc.perform(get("/admin/registry/summary"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void givenRequestWithCorrelationId_whenCallAnyEndpoint_thenSameIdEchoed() throws Exception {
        mockMvc.perform(get("/admin/registry/summary")
                        .header("X-Request-Id", "my-trace-id-12345"))
                .andExpect(header().string("X-Request-Id", "my-trace-id-12345"));
    }
}
