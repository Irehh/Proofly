package com.proofly;

import com.proofly.notification.NotificationDispatcherJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecoveryAndNotificationIntegrationTest {

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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
    }

    @Autowired MockMvc mockMvc;
    @Autowired NotificationDispatcherJob notificationDispatcherJob;
    @Autowired @Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource;

    @Test
    void signupResetAndStaleProcessingRecoveryWorkEndToEnd() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup-org")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgName": "Enugu Textiles",
                                  "slug": "enugu-textiles",
                                  "fullName": "Ngozi Enugu",
                                  "email": "ngozi@enugutextiles.example",
                                  "password": "StrongPassword!234",
                                  "clientType": "MOBILE"
                                }
                                """))
                .andExpect(status().isCreated());

        // Non-enumerating password reset request for existing and unknown emails
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ngozi@enugutextiles.example\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@unknown.example\"}"))
                .andExpect(status().isAccepted());

        JdbcClient jdbc = JdbcClient.create(controlPlaneDataSource);
        Integer verifyQueued = jdbc.sql("""
                SELECT COUNT(1) FROM notification_outbox
                WHERE recipient = 'ngozi@enugutextiles.example' AND template_code = 'WELCOME_VERIFY_EMAIL'
                """).query(Integer.class).single();
        Integer resetQueued = jdbc.sql("""
                SELECT COUNT(1) FROM notification_outbox
                WHERE recipient = 'ngozi@enugutextiles.example' AND template_code = 'PASSWORD_RESET'
                """).query(Integer.class).single();

        assertThat(verifyQueued).isEqualTo(1);
        assertThat(resetQueued).isEqualTo(1);

        // Simulate a worker crash mid-send leaving an item stuck in PROCESSING past timeout
        UUID stuckId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification_outbox
                (id, channel, recipient, template_code, subject, payload_jsonb, idempotency_key,
                 status, attempt_count, max_attempts, processing_started_at, correlation_id)
                VALUES (:id, 'EMAIL', 'stuck@enugutextiles.example', 'PASSWORD_RESET', 'Stuck',
                        '{}'::jsonb, :ikey, 'PROCESSING', 0, 5, NOW() - INTERVAL '10 minutes', 'corr-stuck')
                """)
                .param("id", stuckId)
                .param("ikey", "stuck:" + stuckId)
                .update();

        int recovered = notificationDispatcherJob.recoverStuckProcessing();
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        String recoveredStatus = jdbc.sql("SELECT status FROM notification_outbox WHERE id = :id")
                .param("id", stuckId)
                .query(String.class)
                .single();
        assertThat(recoveredStatus).isEqualTo("FAILED");
    }
}
