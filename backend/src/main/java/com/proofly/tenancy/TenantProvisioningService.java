package com.proofly.tenancy;

import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.common.TenantId;
import com.zaxxer.hikari.HikariDataSource;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Idempotent, resumable tenant database provisioning workflow:
 * PENDING -> PROVISIONING (CREATE DATABASE -> Flyway tenant track -> seed owner & settings) -> ACTIVE.
 */
@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);
    private static final Pattern SAFE_DB_IDENT = Pattern.compile("^[a-z0-9_]{3,63}$");
    private static final UUID FREE_PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public record ProvisionRequest(
            String orgName,
            String slug,
            String defaultTimezone,
            String ownerFullName,
            String ownerEmail,
            String ownerPhoneE164,
            String ownerPasswordHash
    ) {}

    public record ProvisionResult(
            UUID tenantId,
            String slug,
            UUID ownerUserId,
            UUID ownerMembershipId,
            String schemaVersion
    ) {}

    private final DataSource controlPlaneDataSource;
    private final JdbcClient controlJdbc;
    private final TenancyProperties props;
    private final TenantDataSourceRegistry registry;
    private final TenantDirectoryService directoryService;
    private final ApplicationEventPublisher events;

    public TenantProvisioningService(@Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
                                     TenancyProperties props,
                                     TenantDataSourceRegistry registry,
                                     TenantDirectoryService directoryService,
                                     ApplicationEventPublisher events) {
        this.controlPlaneDataSource = controlPlaneDataSource;
        this.controlJdbc = JdbcClient.create(controlPlaneDataSource);
        this.props = props;
        this.registry = registry;
        this.directoryService = directoryService;
        this.events = events;
    }

    public ProvisionResult provisionNewOrganization(ProvisionRequest req) {
        String normalizedSlug = normalizeSlug(req.slug());
        if (directoryService.slugExists(normalizedSlug)) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT,
                    "Organization slug is already taken");
        }
        if (directoryService.findByEmail(req.ownerEmail()).isPresent()) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT,
                    "An account with this email already exists");
        }
        if (req.ownerPhoneE164() != null && directoryService.findByPhone(req.ownerPhoneE164()).isPresent()) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT,
                    "An account with this phone number already exists");
        }

        UUID tenantId = UUID.randomUUID();
        String dbName = buildSafeDbName(tenantId);

        controlJdbc.sql("""
                INSERT INTO tenants (id, slug, name, status, db_host, db_port, db_name, db_secret_ref,
                                     schema_version, current_plan_id, default_currency)
                VALUES (:id, :slug, :name, 'PENDING', :host, :port, :dbName, :secretRef,
                        '0', :planId, 'NGN')
                """)
                .param("id", tenantId)
                .param("slug", normalizedSlug)
                .param("name", req.orgName().trim())
                .param("host", props.defaultDbHost())
                .param("port", props.defaultDbPort())
                .param("dbName", dbName)
                .param("secretRef", "env:PROOFLY_TENANT_DB_PASSWORD")
                .param("planId", FREE_PLAN_ID)
                .update();

        return executeProvisioningSteps(tenantId, req);
    }

    public ProvisionResult resumeProvisioning(UUID tenantId, ProvisionRequest seedInfoIfMissing) {
        return executeProvisioningSteps(tenantId, seedInfoIfMissing);
    }

    private ProvisionResult executeProvisioningSteps(UUID tenantId, ProvisionRequest req) {
        TenantSummary tenant = directoryService.findTenantById(tenantId)
                .orElseThrow(() -> new ProoflyException(
                        HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Tenant not found"));

        if ("ACTIVE".equals(tenant.status())) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT,
                    "Tenant is already active");
        }

        controlJdbc.sql("""
                UPDATE tenants SET status = 'PROVISIONING', provisioning_error = NULL, updated_at = NOW()
                WHERE id = :id
                """)
                .param("id", tenantId)
                .update();

        try {
            ensureDatabaseExists(tenant.dbName());

            try (HikariDataSource tenantDs = registry.buildDirectPoolForProvisioning(
                    tenant.dbHost(), tenant.dbPort(), tenant.dbName(), tenant.slug())) {

                var migrateResult = Flyway.configure()
                        .dataSource(tenantDs)
                        .locations("classpath:db/migration/tenant")
                        .load()
                        .migrate();

                String schemaVersion = migrateResult.targetSchemaVersion != null
                        ? migrateResult.targetSchemaVersion
                        : props.expectedTenantSchemaVersion();

                UUID ownerUserId = UUID.randomUUID();
                UUID ownerMembershipId = UUID.randomUUID();

                if (req != null) {
                    JdbcClient tenantJdbc = JdbcClient.create(tenantDs);
                    Integer existingUsers = tenantJdbc.sql("SELECT COUNT(1) FROM users")
                            .query(Integer.class)
                            .single();
                    if (existingUsers == null || existingUsers == 0) {
                        tenantJdbc.sql("""
                                INSERT INTO organization_settings (id, org_name, default_timezone)
                                VALUES (:id, :orgName, :tz)
                                """)
                                .param("id", UUID.randomUUID())
                                .param("orgName", req.orgName().trim())
                                .param("tz", req.defaultTimezone() != null ? req.defaultTimezone() : "Africa/Lagos")
                                .update();

                        tenantJdbc.sql("""
                                INSERT INTO users (id, email, phone_e164, full_name, password_hash, status)
                                VALUES (:id, :email, :phone, :fullName, :pwHash, 'ACTIVE')
                                """)
                                .param("id", ownerUserId)
                                .param("email", req.ownerEmail().trim().toLowerCase(Locale.ROOT))
                                .param("phone", req.ownerPhoneE164())
                                .param("fullName", req.ownerFullName().trim())
                                .param("pwHash", req.ownerPasswordHash())
                                .update();

                        tenantJdbc.sql("""
                                INSERT INTO memberships (id, user_id, role, status)
                                VALUES (:id, :userId, 'SUPER_ADMIN', 'ACTIVE')
                                """)
                                .param("id", ownerMembershipId)
                                .param("userId", ownerUserId)
                                .update();

                        directoryService.registerUser(tenantId, ownerUserId, req.ownerEmail(), req.ownerPhoneE164());
                    } else {
                        ownerUserId = tenantJdbc.sql("SELECT id FROM users ORDER BY created_at ASC LIMIT 1")
                                .query(UUID.class).single();
                        ownerMembershipId = tenantJdbc.sql("SELECT id FROM memberships WHERE user_id = :uid")
                                .param("uid", ownerUserId)
                                .query(UUID.class).single();
                    }
                }

                controlJdbc.sql("""
                        UPDATE tenants
                        SET status = 'ACTIVE', schema_version = :ver, provisioning_error = NULL, updated_at = NOW()
                        WHERE id = :id
                        """)
                        .param("ver", schemaVersion)
                        .param("id", tenantId)
                        .update();

                registry.evict(new TenantId(tenantId));
                events.publishEvent(new TenantProvisionedEvent(tenantId, tenant.slug(), schemaVersion));
                return new ProvisionResult(tenantId, tenant.slug(), ownerUserId, ownerMembershipId, schemaVersion);
            }
        } catch (Exception ex) {
            log.error("Tenant provisioning failed for tenantId={}", tenantId, ex);
            controlJdbc.sql("""
                    UPDATE tenants
                    SET status = 'PROVISIONING',
                        provisioning_error = :err,
                        retry_count = retry_count + 1,
                        updated_at = NOW()
                    WHERE id = :id
                    """)
                    .param("err", ex.getMessage() != null ? ex.getMessage().substring(0, Math.min(500, ex.getMessage().length())) : "unknown")
                    .param("id", tenantId)
                    .update();
            throw new ProoflyException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCode.TENANT_PROVISIONING_FAILED,
                    "Organization provisioning failed and is queued for recovery", ex);
        }
    }

    @Scheduled(fixedDelayString = "PT60S")
    @SchedulerLock(name = "retryStuckProvisioningTenants", lockAtLeastFor = "PT15S", lockAtMostFor = "PT4M")
    public void retryStuckProvisioningTenants() {
        List<UUID> stuckIds = controlJdbc.sql("""
                SELECT id FROM tenants
                WHERE status = 'PROVISIONING'
                  AND retry_count < 5
                  AND updated_at < NOW() - INTERVAL '30 seconds'
                LIMIT 10
                """)
                .query(UUID.class)
                .list();

        for (UUID tenantId : stuckIds) {
            try {
                log.info("Retrying stuck provisioning for tenantId={}", tenantId);
                resumeProvisioning(tenantId, null);
            } catch (Exception ex) {
                log.warn("Retry provisioning failed for tenantId={}: {}", tenantId, ex.getMessage());
            }
        }
    }

    private void ensureDatabaseExists(String dbName) throws Exception {
        if (!SAFE_DB_IDENT.matcher(dbName).matches()) {
            throw new IllegalArgumentException("Unsafe tenant db_name: " + dbName);
        }
        try (Connection conn = controlPlaneDataSource.getConnection();
             PreparedStatement check = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            check.setString(1, dbName);
            try (ResultSet rs = check.executeQuery()) {
                if (!rs.next()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("CREATE DATABASE " + dbName);
                    }
                }
            }
        }
    }

    private static String normalizeSlug(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
    }

    private static String buildSafeDbName(UUID tenantId) {
        return "proofly_t_" + tenantId.toString().replace("-", "");
    }
}
