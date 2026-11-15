package com.proofly.tenancy;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

@Service
public class TenantDirectoryService implements TenantSummary.DirectoryLookup {

    private final JdbcClient controlJdbc;

    public TenantDirectoryService(@Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource) {
        this.controlJdbc = JdbcClient.create(controlPlaneDataSource);
    }

    @Override
    public Optional<TenantSummary.DirectoryMatch> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return controlJdbc.sql("""
                SELECT tenant_id, tenant_user_id, status
                FROM global_user_directory
                WHERE LOWER(email) = LOWER(:email)
                """)
                .param("email", email.trim())
                .query(TenantSummary.DirectoryMatch.class)
                .optional();
    }

    @Override
    public Optional<TenantSummary.DirectoryMatch> findByPhone(String phoneE164) {
        if (phoneE164 == null || phoneE164.isBlank()) {
            return Optional.empty();
        }
        return controlJdbc.sql("""
                SELECT tenant_id, tenant_user_id, status
                FROM global_user_directory
                WHERE phone_e164 = :phone
                """)
                .param("phone", phoneE164.trim())
                .query(TenantSummary.DirectoryMatch.class)
                .optional();
    }

    public void registerUser(UUID tenantId, UUID tenantUserId, String email, String phoneE164) {
        controlJdbc.sql("""
                INSERT INTO global_user_directory (id, tenant_id, email, phone_e164, tenant_user_id, status)
                VALUES (:id, :tenantId, :email, :phone, :tenantUserId, 'ACTIVE')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("email", email != null ? email.trim().toLowerCase() : null)
                .param("phone", phoneE164 != null ? phoneE164.trim() : null)
                .param("tenantUserId", tenantUserId)
                .update();
    }

    public Optional<TenantSummary> findTenantById(UUID tenantId) {
        return controlJdbc.sql("""
                SELECT id, slug, name, status, db_host, db_port, db_name, schema_version,
                       current_plan_id, default_currency, created_at
                FROM tenants
                WHERE id = :id
                """)
                .param("id", tenantId)
                .query(TenantSummary.class)
                .optional();
    }

    public TenantSummary.PlanQuota findTenantQuota(UUID tenantId) {
        return controlJdbc.sql("""
                SELECT p.code, p.name, p.max_members, p.max_sites
                FROM tenants t
                JOIN plans p ON p.id = t.current_plan_id
                WHERE t.id = :id
                """)
                .param("id", tenantId)
                .query((rs, rowNum) -> new TenantSummary.PlanQuota(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getInt("max_members"),
                        rs.getInt("max_sites")
                ))
                .single();
    }

    public void updateUserStatus(UUID tenantId, UUID tenantUserId, String status) {
        controlJdbc.sql("""
                UPDATE global_user_directory
                SET status = :status
                WHERE tenant_id = :tenantId AND tenant_user_id = :tenantUserId
                """)
                .param("status", status)
                .param("tenantId", tenantId)
                .param("tenantUserId", tenantUserId)
                .update();
    }

    public boolean slugExists(String slug) {
        Integer count = controlJdbc.sql("SELECT COUNT(1) FROM tenants WHERE slug = :slug")
                .param("slug", slug)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }
}
