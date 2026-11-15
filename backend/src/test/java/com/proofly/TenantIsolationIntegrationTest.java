package com.proofly;

import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.common.TenantId;
import com.proofly.tenancy.TenantContext;
import com.proofly.tenancy.TenantContextTaskDecorator;
import com.proofly.tenancy.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TenantIsolationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("proofly_control")
            .withUsername("proofly_admin")
            .withPassword("proofly_test_pw");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("proofly.control-plane.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("proofly.control-plane.username", POSTGRES::getUsername);
        registry.add("proofly.control-plane.password", POSTGRES::getPassword);
        registry.add("proofly.tenancy.default-db-host", POSTGRES::getHost);
        registry.add("proofly.tenancy.default-db-port", POSTGRES::getFirstMappedPort);
        registry.add("proofly.tenancy.db-username", POSTGRES::getUsername);
        registry.add("proofly.tenancy.db-password", POSTGRES::getPassword);
        registry.add("proofly.security.jwt.cookie-secure", () -> "false");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
    }

    @Autowired MockMvc mockMvc;
    @Autowired DataSource tenantRoutingDataSource;
    @Autowired TenantContextTaskDecorator taskDecorator;
    @Autowired TenantProvisioningService provisioningService;

    @Test
    void forgedTenantHeaderCannotCrossTenantBoundary() throws Exception {
        String signupA = mockMvc.perform(post("/api/v1/auth/signup-org")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgName": "Alpha Logistics",
                                  "slug": "alpha-logistics",
                                  "fullName": "Ada Alpha",
                                  "email": "ada@alpha.example",
                                  "password": "StrongPassword!234",
                                  "clientType": "MOBILE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String signupB = mockMvc.perform(post("/api/v1/auth/signup-org")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgName": "Beta Construction",
                                  "slug": "beta-construction",
                                  "fullName": "Bola Beta",
                                  "email": "bola@beta.example",
                                  "password": "StrongPassword!234",
                                  "clientType": "MOBILE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String tokenA = mapper.readTree(signupA).get("accessToken").asText();
        String tenantIdA = mapper.readTree(signupA).get("user").get("tenantId").asText();
        String tenantIdB = mapper.readTree(signupB).get("user").get("tenantId").asText();

        // Even with a forged X-Tenant-ID header pointing to Tenant B, JWT tid strictly pins Tenant A
        mockMvc.perform(get("/api/v1/tenants/me")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("X-Tenant-ID", tenantIdB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantIdA))
                .andExpect(jsonPath("$.slug").value("alpha-logistics"));
    }

    @Test
    void asyncExecutionWithoutTenantContextFailsLoudly() {
        TenantContext.clear();
        Runnable wrapped = taskDecorator.decorate(() -> {
            JdbcClient.create(tenantRoutingDataSource).sql("SELECT COUNT(1) FROM users").query(Integer.class).single();
        });

        assertThatThrownBy(() -> CompletableFuture.runAsync(wrapped).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ProoflyException.class)
                .satisfies(ex -> {
                    ProoflyException pe = (ProoflyException) ex.getCause();
                    assertThat(pe.getErrorCode()).isEqualTo(ErrorCode.TENANT_CONTEXT_MISSING);
                });
    }

    @Test
    void explicitTenantContextInAsyncTaskIsolatesDataPerTenant() {
        TenantId missing = new TenantId(UUID.randomUUID());
        assertThatThrownBy(() -> TenantContext.runWithTenant(missing, () ->
                JdbcClient.create(tenantRoutingDataSource).sql("SELECT 1").query(Integer.class).single()))
                .isInstanceOf(ProoflyException.class);
        assertThat(TenantContext.current()).isEmpty();
    }
}
