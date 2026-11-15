package com.proofly.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded-concurrency orchestrator that migrates ACTIVE tenant databases out-of-band
 * without blocking application startup, halting if failure threshold is breached.
 */
@Service
public class TenantMigrationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationOrchestrator.class);

    public record BatchReport(int totalAttempted, int succeeded, int failed, boolean haltedEarly) {}

    private final JdbcClient controlJdbc;
    private final TenancyProperties props;
    private final TenantDataSourceRegistry registry;

    public TenantMigrationOrchestrator(@Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
                                       TenancyProperties props,
                                       TenantDataSourceRegistry registry) {
        this.controlJdbc = JdbcClient.create(controlPlaneDataSource);
        this.props = props;
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${proofly.tenancy.drift-check-interval:PT30M}")
    @SchedulerLock(name = "checkTenantSchemaDrift", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void checkTenantSchemaDrift() {
        if (!props.driftCheckEnabled()) {
            return;
        }
        List<TenantSummary> drifted = controlJdbc.sql("""
                SELECT id, slug, name, status, db_host, db_port, db_name, schema_version,
                       current_plan_id, default_currency, created_at
                FROM tenants
                WHERE status = 'ACTIVE' AND schema_version <> :expected
                ORDER BY updated_at ASC
                LIMIT 50
                """)
                .param("expected", props.expectedTenantSchemaVersion())
                .query(TenantSummary.class)
                .list();

        if (drifted.isEmpty()) {
            return;
        }
        log.warn("Detected {} ACTIVE tenant(s) behind expected schema_version={}; running bounded migration",
                drifted.size(), props.expectedTenantSchemaVersion());
        BatchReport report = migrateTenants(drifted);
        log.info("Tenant schema drift check finished: attempted={} succeeded={} failed={} haltedEarly={}",
                report.totalAttempted(), report.succeeded(), report.failed(), report.haltedEarly());
    }

    public BatchReport migrateAllActiveTenants() {
        List<TenantSummary> activeTenants = controlJdbc.sql("""
                SELECT id, slug, name, status, db_host, db_port, db_name, schema_version,
                       current_plan_id, default_currency, created_at
                FROM tenants
                WHERE status = 'ACTIVE'
                ORDER BY created_at ASC
                """)
                .query(TenantSummary.class)
                .list();
        return migrateTenants(activeTenants);
    }

    private BatchReport migrateTenants(List<TenantSummary> tenants) {
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicBoolean halted = new AtomicBoolean(false);

        int concurrency = Math.max(1, props.migrationConcurrency());
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (TenantSummary tenant : tenants) {
                tasks.add(() -> {
                    if (halted.get()) {
                        return null;
                    }
                    try {
                        migrateSingleTenant(tenant);
                        succeeded.incrementAndGet();
                    } catch (Exception ex) {
                        int failCount = failed.incrementAndGet();
                        log.error("Tenant migration failed for tenant={} ({}/{} threshold)",
                                tenant.id(), failCount, props.migrationFailureThreshold(), ex);
                        if (failCount >= props.migrationFailureThreshold()) {
                            halted.set(true);
                        }
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get();
            }
        } catch (Exception ex) {
            Thread.currentThread().interrupt();
            log.error("Tenant migration batch interrupted", ex);
        }

        return new BatchReport(succeeded.get() + failed.get(), succeeded.get(), failed.get(), halted.get());
    }

    private void migrateSingleTenant(TenantSummary tenant) {
        try (HikariDataSource ds = registry.buildDirectPoolForProvisioning(
                tenant.dbHost(), tenant.dbPort(), tenant.dbName(), tenant.slug() + "-mig")) {
            var result = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration/tenant")
                    .load()
                    .migrate();
            String version = result.targetSchemaVersion != null
                    ? result.targetSchemaVersion
                    : props.expectedTenantSchemaVersion();
            controlJdbc.sql("UPDATE tenants SET schema_version = :ver, updated_at = NOW() WHERE id = :id")
                    .param("ver", version)
                    .param("id", tenant.id())
                    .update();
            registry.evict(tenant.tenantId());
        }
    }
}
