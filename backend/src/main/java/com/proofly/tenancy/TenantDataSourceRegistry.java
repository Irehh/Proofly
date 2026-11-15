package com.proofly.tenancy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.common.TenantId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maintains a bounded, LRU-evicted pool of small HikariCP DataSources per tenant DB,
 * enforcing a global connection cap and optional PgBouncer transaction-pooling compatibility.
 */
@Component
public class TenantDataSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSourceRegistry.class);

    private final TenancyProperties props;
    private final JdbcClient controlJdbc;
    private final Cache<UUID, HikariDataSource> poolCache;
    private final AtomicInteger allocatedMaxConnections = new AtomicInteger(0);

    public TenantDataSourceRegistry(TenancyProperties props,
                                    @Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource) {
        this.props = props;
        this.controlJdbc = JdbcClient.create(controlPlaneDataSource);
        int maxPoolsByCap = Math.max(1, props.globalConnectionCap() / Math.max(1, props.perTenantMaxPoolSize()));
        int effectiveMaxEntries = Math.min(props.maxCachedPools(), maxPoolsByCap);

        this.poolCache = Caffeine.newBuilder()
                .maximumSize(effectiveMaxEntries)
                .expireAfterAccess(Duration.ofMinutes(20))
                .removalListener((UUID key, HikariDataSource ds, RemovalCause cause) -> {
                    if (ds != null) {
                        allocatedMaxConnections.addAndGet(-props.perTenantMaxPoolSize());
                        log.info("Evicting HikariCP pool for tenant={} cause={}", key, cause);
                        ds.close();
                    }
                })
                .build();
    }

    public DataSource getOrCreate(TenantId tenantId) {
        HikariDataSource ds = poolCache.get(tenantId.value(), this::createAndVerifyTenantPool);
        if (ds == null) {
            throw new ProoflyException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.TENANT_NOT_ACTIVE,
                    "Unable to acquire tenant datasource");
        }
        return ds;
    }

    public void evict(TenantId tenantId) {
        poolCache.invalidate(tenantId.value());
    }

    public int activePoolCount() {
        return (int) poolCache.estimatedSize();
    }

    public int allocatedConnectionCeiling() {
        return allocatedMaxConnections.get();
    }

    private HikariDataSource createAndVerifyTenantPool(UUID tenantUuid) {
        TenantSummary tenant = controlJdbc.sql("""
                SELECT id, slug, name, status, db_host, db_port, db_name, schema_version,
                       current_plan_id, default_currency, created_at
                FROM tenants
                WHERE id = :id
                """)
                .param("id", tenantUuid)
                .query(TenantSummary.class)
                .optional()
                .orElseThrow(() -> new ProoflyException(
                        HttpStatus.NOT_FOUND, ErrorCode.TENANT_NOT_ACTIVE, "Tenant not found"));

        if (!"ACTIVE".equals(tenant.status())) {
            throw new ProoflyException(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.TENANT_NOT_ACTIVE,
                    "Tenant is currently " + tenant.status()
            );
        }

        HikariDataSource ds = buildHikariDataSource(tenant.dbHost(), tenant.dbPort(), tenant.dbName(), tenant.slug());
        allocatedMaxConnections.addAndGet(props.perTenantMaxPoolSize());

        // Lazy schema-version verification on first pool creation
        if (!props.expectedTenantSchemaVersion().equals(tenant.schemaVersion())) {
            log.info("Lazy migrating tenant={} from schema_version={} to expected={}",
                    tenant.id(), tenant.schemaVersion(), props.expectedTenantSchemaVersion());
            var result = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration/tenant")
                    .baselineOnMigrate(false)
                    .load()
                    .migrate();
            String currentVersion = result.targetSchemaVersion != null
                    ? result.targetSchemaVersion
                    : props.expectedTenantSchemaVersion();
            controlJdbc.sql("UPDATE tenants SET schema_version = :ver, updated_at = NOW() WHERE id = :id")
                    .param("ver", currentVersion)
                    .param("id", tenant.id())
                    .update();
        }

        return ds;
    }

    public HikariDataSource buildDirectPoolForProvisioning(String dbHost, int dbPort, String dbName, String slug) {
        return buildHikariDataSource(dbHost, dbPort, dbName, slug + "-prov");
    }

    private HikariDataSource buildHikariDataSource(String dbHost, int dbPort, String dbName, String poolSuffix) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("proofly-tenant-" + poolSuffix);
        cfg.setJdbcUrl("jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName);
        cfg.setUsername(props.dbUsername());
        cfg.setPassword(props.dbPassword());
        cfg.setMaximumPoolSize(props.perTenantMaxPoolSize());
        cfg.setMinimumIdle(props.perTenantMinIdle());
        cfg.setIdleTimeout(Duration.ofMinutes(3).toMillis());
        cfg.setMaxLifetime(Duration.ofMinutes(15).toMillis());
        cfg.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
        cfg.setLeakDetectionThreshold(Duration.ofSeconds(15).toMillis());

        if (props.usePgbouncer()) {
            // Required for PgBouncer transaction pooling
            cfg.addDataSourceProperty("prepareThreshold", "0");
            cfg.addDataSourceProperty("preparedStatementCacheQueries", "0");
        }
        return new HikariDataSource(cfg);
    }

    @PreDestroy
    public void shutdown() {
        poolCache.invalidateAll();
        poolCache.cleanUp();
    }
}
