package com.proofly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofly.common.TenantId;
import com.proofly.identity.Argon2PasswordService;
import com.proofly.tenancy.TenantContext;
import com.proofly.tenancy.TenantDirectoryService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthSecurityIntegrationTest {

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
    @Autowired ObjectMapper objectMapper;
    @Autowired DataSource tenantRoutingDataSource;
    @Autowired Argon2PasswordService passwordService;
    @Autowired TenantDirectoryService directoryService;

    @Test
    void refreshTokenReuseRevokesEntireFamilyAndDeviceRevocationBlocksRefresh() throws Exception {
        String signupJson = mockMvc.perform(post("/api/v1/auth/signup-org")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgName": "Kano Solar",
                                  "slug": "kano-solar",
                                  "fullName": "Musa Kano",
                                  "email": "musa@kanosolar.example",
                                  "password": "StrongPassword!234",
                                  "deviceFingerprint": "ios-device-1",
                                  "deviceName": "iPhone 15",
                                  "clientType": "MOBILE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode signup = objectMapper.readTree(signupJson);
        String refresh1 = signup.get("refreshToken").asText();

        // Legitimate rotation -> issues refresh2
        String rotatedJson = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Type", "MOBILE")
                        .content("{\"refreshToken\":\"" + refresh1 + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode rotated = objectMapper.readTree(rotatedJson);
        String refresh2 = rotated.get("refreshToken").asText();
        String access2 = rotated.get("accessToken").asText();
        String deviceId = rotated.get("deviceId").asText();

        // Replay of already-rotated refresh1 must detect reuse and revoke the whole family
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Type", "MOBILE")
                        .content("{\"refreshToken\":\"" + refresh1 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("PROOFLY_REFRESH_TOKEN_REUSED"));

        // Now even refresh2 from the compromised family is rejected
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Type", "MOBILE")
                        .content("{\"refreshToken\":\"" + refresh2 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("PROOFLY_TOKEN_REVOKED"));

        // Re-login and revoke device explicitly
        String reloginJson = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "EMAIL_PASSWORD",
                                  "email": "musa@kanosolar.example",
                                  "password": "StrongPassword!234",
                                  "deviceFingerprint": "ios-device-1",
                                  "clientType": "MOBILE"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String refresh3 = objectMapper.readTree(reloginJson).get("refreshToken").asText();
        mockMvc.perform(delete("/api/v1/devices/" + deviceId)
                        .header("Authorization", "Bearer " + access2))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Type", "MOBILE")
                        .content("{\"refreshToken\":\"" + refresh3 + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pinBruteForceEnforcesLockout() throws Exception {
        String signupJson = mockMvc.perform(post("/api/v1/auth/signup-org")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgName": "Lagos Port Ops",
                                  "slug": "lagos-port-ops",
                                  "fullName": "Tunde Lagos",
                                  "email": "tunde@lagosport.example",
                                  "phoneE164": "+2348012345678",
                                  "password": "StrongPassword!234",
                                  "clientType": "MOBILE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID tenantId = UUID.fromString(objectMapper.readTree(signupJson).get("user").get("tenantId").asText());
        UUID userId = UUID.fromString(objectMapper.readTree(signupJson).get("user").get("userId").asText());

        TenantContext.runWithTenant(new TenantId(tenantId), () -> {
            JdbcClient.create(tenantRoutingDataSource)
                    .sql("UPDATE users SET pin_hash = :ph WHERE id = :uid")
                    .param("ph", passwordService.hash("4829"))
                    .param("uid", userId)
                    .update();
        });

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "method": "PHONE_PIN",
                                      "phoneE164": "+2348012345678",
                                      "pin": "0000",
                                      "clientType": "MOBILE"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        // 5th attempt triggers lockout (HTTP 423)
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "PHONE_PIN",
                                  "phoneE164": "+2348012345678",
                                  "pin": "0000",
                                  "clientType": "MOBILE"
                                }
                                """))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.errorCode").value("PROOFLY_PIN_LOCKED"));
    }
}
