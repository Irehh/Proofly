package com.proofly.organization;

import com.proofly.audit.AuditWriter;
import com.proofly.common.AppProperties;
import com.proofly.common.AuthenticatedActor;
import com.proofly.common.ErrorCode;
import com.proofly.common.HashChainUtil;
import com.proofly.common.ProoflyException;
import com.proofly.common.TenantId;
import com.proofly.identity.Argon2PasswordService;
import com.proofly.notification.NotificationOutboxService;
import com.proofly.tenancy.TenantContext;
import com.proofly.tenancy.TenantDirectoryService;
import com.proofly.tenancy.TenantSummary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrganizationService implements OrganizationPublicApi {

    public record UpdateOrgSettingsRequest(
            String orgName,
            String defaultTimezone,
            Integer lateGraceMinutes,
            Integer overtimeThresholdMinutes,
            Integer maxGpsAccuracyMeters
    ) {}

    public record InviteStaffRequest(
            String inviteType,
            String fullName,
            String email,
            String phoneE164,
            String initialPin,
            String role,
            UUID managerMembershipId,
            String jobTitle,
            String employeeCode
    ) {}

    public record InvitationView(
            UUID id,
            String inviteType,
            String email,
            String phoneE164,
            String role,
            UUID managerMembershipId,
            Instant expiresAt,
            Instant acceptedAt,
            Instant createdAt
    ) {}

    public record AcceptInviteRequest(
            UUID tenantId,
            String token,
            String fullName,
            String password
    ) {}

    public record UpdateMemberRequest(
            String role,
            String status,
            UUID managerMembershipId,
            String jobTitle,
            String employeeCode
    ) {}

    private final JdbcClient tenantJdbc;
    private final TenantDirectoryService directoryService;
    private final Argon2PasswordService passwordService;
    private final AuditWriter auditWriter;
    private final NotificationOutboxService outboxService;
    private final AppProperties appProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public OrganizationService(DataSource tenantRoutingDataSource,
                               TenantDirectoryService directoryService,
                               Argon2PasswordService passwordService,
                               AuditWriter auditWriter,
                               NotificationOutboxService outboxService,
                               AppProperties appProperties) {
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
        this.directoryService = directoryService;
        this.passwordService = passwordService;
        this.auditWriter = auditWriter;
        this.outboxService = outboxService;
        this.appProperties = appProperties;
    }

    @Override
    public OrgSettingsView getOrganizationSettings() {
        TenantContext.requireCurrent();
        return tenantJdbc.sql("""
                SELECT org_name, default_timezone, late_grace_minutes,
                       overtime_threshold_minutes, max_gps_accuracy_meters, location_retention_days
                FROM organization_settings
                LIMIT 1
                """)
                .query(OrgSettingsView.class)
                .single();
    }

    @Transactional
    public OrgSettingsView updateOrganizationSettings(AuthenticatedActor actor,
                                                      UpdateOrgSettingsRequest req,
                                                      String ipAddress) {
        OrgSettingsView before = getOrganizationSettings();
        String nextOrgName = req.orgName() != null && !req.orgName().isBlank() ? req.orgName().trim() : before.orgName();
        String nextTz = validateTimezone(req.defaultTimezone(), before.defaultTimezone());
        int nextLateGrace = req.lateGraceMinutes() != null ? clamp(req.lateGraceMinutes(), 0, 240) : before.lateGraceMinutes();
        int nextOvertime = req.overtimeThresholdMinutes() != null ? clamp(req.overtimeThresholdMinutes(), 60, 1440) : before.overtimeThresholdMinutes();
        int nextMaxGps = req.maxGpsAccuracyMeters() != null ? clamp(req.maxGpsAccuracyMeters(), 10, 500) : before.maxGpsAccuracyMeters();

        tenantJdbc.sql("""
                UPDATE organization_settings
                SET org_name = :orgName,
                    default_timezone = :tz,
                    late_grace_minutes = :lateGrace,
                    overtime_threshold_minutes = :overtime,
                    max_gps_accuracy_meters = :maxGps,
                    updated_at = NOW()
                """)
                .param("orgName", nextOrgName)
                .param("tz", nextTz)
                .param("lateGrace", nextLateGrace)
                .param("overtime", nextOvertime)
                .param("maxGps", nextMaxGps)
                .update();

        OrgSettingsView after = getOrganizationSettings();
        auditWriter.writeTenantEvent(actor.membershipId(), "ORG_SETTINGS_UPDATED", "organization_settings",
                actor.tenantId().toString(), before, after, ipAddress, null);
        return after;
    }

    @Override
    public Optional<MemberSummary> findMemberByMembershipId(UUID membershipId) {
        TenantContext.requireCurrent();
        return fetchMembersWithClause("WHERE m.id = :mid", Map.of("mid", membershipId)).stream().findFirst();
    }

    @Override
    public List<MemberSummary> listVisibleMembers(UUID actorMembershipId, String actorRole) {
        TenantContext.requireCurrent();
        if ("SUPER_ADMIN".equals(actorRole) || "ADMIN".equals(actorRole)) {
            return fetchMembersWithClause("ORDER BY m.joined_at ASC", Map.of());
        }
        if ("MANAGER".equals(actorRole)) {
            return fetchMembersWithClause(
                    "WHERE m.id = :mid OR m.manager_membership_id = :mid ORDER BY m.joined_at ASC",
                    Map.of("mid", actorMembershipId)
            );
        }
        return fetchMembersWithClause("WHERE m.id = :mid", Map.of("mid", actorMembershipId));
    }

    @Override
    public boolean canManageTarget(UUID actorMembershipId, String actorRole, UUID targetMembershipId) {
        if ("SUPER_ADMIN".equals(actorRole) || "ADMIN".equals(actorRole)) {
            return true;
        }
        if ("MANAGER".equals(actorRole)) {
            Integer count = tenantJdbc.sql("""
                    SELECT COUNT(1) FROM memberships
                    WHERE id = :target AND manager_membership_id = :mgr
                    """)
                    .param("target", targetMembershipId)
                    .param("mgr", actorMembershipId)
                    .query(Integer.class)
                    .single();
            return count != null && count > 0;
        }
        return false;
    }

    @Transactional
    public MemberSummary inviteStaff(AuthenticatedActor actor, InviteStaffRequest req, String ipAddress) {
        UUID tenantId = TenantContext.requireCurrent().value();
        TenantSummary.PlanQuota quota = directoryService.findTenantQuota(tenantId);

        Integer activeCount = tenantJdbc.sql("""
                SELECT COUNT(1) FROM memberships WHERE status <> 'OFFBOARDED'
                """)
                .query(Integer.class)
                .single();
        if (activeCount != null && activeCount >= quota.maxMembers()) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.VALIDATION_ERROR,
                    "Member quota reached for " + quota.planName() + " plan (" + quota.maxMembers() + " active seats). Upgrade billing to add more staff.");
        }

        String requestedRole = req.role() != null ? req.role().trim().toUpperCase(Locale.ROOT) : "EMPLOYEE";
        if (!List.of("ADMIN", "MANAGER", "EMPLOYEE").contains(requestedRole)) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Invited role must be ADMIN, MANAGER, or EMPLOYEE");
        }
        if ("ADMIN".equals(requestedRole) && !actor.isSuperAdmin()) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "Only SUPER_ADMIN may invite ADMIN accounts");
        }

        String inviteType = req.inviteType() != null ? req.inviteType().trim().toUpperCase(Locale.ROOT) : "EMAIL";
        String email = req.email() != null && !req.email().isBlank() ? req.email().trim().toLowerCase(Locale.ROOT) : null;
        String phone = req.phoneE164() != null && !req.phoneE164().isBlank() ? req.phoneE164().trim() : null;

        if ("EMAIL".equals(inviteType) && email == null) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Work email is required for EMAIL invite");
        }
        if ("PHONE_PIN".equals(inviteType)) {
            if (phone == null || req.initialPin() == null || !req.initialPin().matches("^\\d{4,8}$")) {
                throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                        "E.164 phone number and 4-8 digit initial PIN are required for PHONE_PIN invite");
            }
        }

        if (email != null && directoryService.findByEmail(email).isPresent()) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT, "Email is already registered in Proofly");
        }
        if (phone != null && directoryService.findByPhone(phone).isPresent()) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT, "Phone number is already registered in Proofly");
        }

        UUID newUserId = UUID.randomUUID();
        UUID newMembershipId = UUID.randomUUID();
        String fullName = req.fullName().trim();
        String orgName = getOrganizationSettings().orgName();

        if ("PHONE_PIN".equals(inviteType)) {
            String pinHash = passwordService.hash(req.initialPin().trim());
            tenantJdbc.sql("""
                    INSERT INTO users (id, email, phone_e164, full_name, pin_hash, status)
                    VALUES (:id, :email, :phone, :name, :pinHash, 'ACTIVE')
                    """)
                    .param("id", newUserId)
                    .param("email", email)
                    .param("phone", phone)
                    .param("name", fullName)
                    .param("pinHash", pinHash)
                    .update();

            tenantJdbc.sql("""
                    INSERT INTO memberships (id, user_id, role, manager_membership_id, job_title, employee_code, status)
                    VALUES (:mid, :uid, :role, :mgr, :title, :empCode, 'ACTIVE')
                    """)
                    .param("mid", newMembershipId)
                    .param("uid", newUserId)
                    .param("role", requestedRole)
                    .param("mgr", req.managerMembershipId())
                    .param("title", trimToNull(req.jobTitle()))
                    .param("empCode", trimToNull(req.employeeCode()))
                    .update();

            directoryService.registerUser(tenantId, newUserId, email, phone);

            outboxService.enqueueSms(
                    tenantId,
                    phone,
                    "STAFF_INVITE_PHONE_PIN",
                    Map.of("orgName", orgName),
                    "invite-sms:" + newMembershipId
            );
        } else {
            String rawToken = generateOpaqueToken();
            String tokenHash = HashChainUtil.sha256Hex(rawToken);
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            tenantJdbc.sql("""
                    INSERT INTO users (id, email, phone_e164, full_name, status)
                    VALUES (:id, :email, :phone, :name, 'INVITED')
                    """)
                    .param("id", newUserId)
                    .param("email", email)
                    .param("phone", phone)
                    .param("name", fullName)
                    .update();

            tenantJdbc.sql("""
                    INSERT INTO memberships (id, user_id, role, manager_membership_id, job_title, employee_code, status)
                    VALUES (:mid, :uid, :role, :mgr, :title, :empCode, 'ACTIVE')
                    """)
                    .param("mid", newMembershipId)
                    .param("uid", newUserId)
                    .param("role", requestedRole)
                    .param("mgr", req.managerMembershipId())
                    .param("title", trimToNull(req.jobTitle()))
                    .param("empCode", trimToNull(req.employeeCode()))
                    .update();

            tenantJdbc.sql("""
                    INSERT INTO invitations
                    (id, invite_type, email, phone_e164, role, manager_membership_id, token_hash, expires_at, invited_by_membership_id)
                    VALUES (:id, 'EMAIL', :email, :phone, :role, :mgr, :tokenHash, :expires, :invitedBy)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("email", email)
                    .param("phone", phone)
                    .param("role", requestedRole)
                    .param("mgr", req.managerMembershipId())
                    .param("tokenHash", tokenHash)
                    .param("expires", Timestamp.from(expiresAt))
                    .param("invitedBy", actor.membershipId())
                    .update();

            directoryService.registerUser(tenantId, newUserId, email, phone);

            String actionUrl = appProperties.frontendBaseUrl() + "/accept-invite?tid=" + tenantId + "&token=" + rawToken;
            outboxService.enqueueEmail(
                    tenantId,
                    email,
                    "STAFF_INVITE_EMAIL",
                    "Join " + orgName + " on Proofly",
                    Map.of("orgName", orgName, "actionUrl", actionUrl),
                    "invite-email:" + newMembershipId
            );
        }

        MemberSummary created = findMemberByMembershipId(newMembershipId).orElseThrow();
        auditWriter.writeTenantEvent(actor.membershipId(), "STAFF_INVITED", "membership",
                newMembershipId.toString(), null, Map.of("role", requestedRole, "inviteType", inviteType),
                ipAddress, null);
        return created;
    }

    public void acceptEmailInvite(AcceptInviteRequest req, String ipAddress) {
        if (req.tenantId() == null || req.token() == null || req.token().isBlank()
                || req.password() == null || req.password().length() < 10) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Valid tenantId, invite token, and 10+ character password are required");
        }

        String tokenHash = HashChainUtil.sha256Hex(req.token().trim());
        String passwordHash = passwordService.hash(req.password());

        TenantContext.runWithTenant(new TenantId(req.tenantId()), () -> {
            record InviteRecord(UUID id, String email, Instant expiresAt, Instant acceptedAt) {}
            InviteRecord invite = tenantJdbc.sql("""
                    SELECT id, email, expires_at, accepted_at
                    FROM invitations
                    WHERE token_hash = :hash
                    """)
                    .param("hash", tokenHash)
                    .query((rs, rowNum) -> new InviteRecord(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("accepted_at") != null ? rs.getTimestamp("accepted_at").toInstant() : null
                    ))
                    .optional()
                    .orElseThrow(() -> new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                            "Invitation token is invalid"));

            if (invite.acceptedAt() != null || invite.expiresAt().isBefore(Instant.now())) {
                throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                        "Invitation token is expired or already accepted");
            }

            UUID userId = tenantJdbc.sql("SELECT id FROM users WHERE LOWER(email) = LOWER(:email)")
                    .param("email", invite.email())
                    .query(UUID.class)
                    .single();

            String nextName = trimToNull(req.fullName());
            if (nextName != null) {
                tenantJdbc.sql("UPDATE users SET password_hash = :hash, full_name = :name, status = 'ACTIVE' WHERE id = :id")
                        .param("hash", passwordHash)
                        .param("name", nextName)
                        .param("id", userId)
                        .update();
            } else {
                tenantJdbc.sql("UPDATE users SET password_hash = :hash, status = 'ACTIVE' WHERE id = :id")
                        .param("hash", passwordHash)
                        .param("id", userId)
                        .update();
            }

            tenantJdbc.sql("UPDATE invitations SET accepted_at = NOW() WHERE id = :id")
                    .param("id", invite.id())
                    .update();

            UUID membershipId = tenantJdbc.sql("SELECT id FROM memberships WHERE user_id = :uid")
                    .param("uid", userId)
                    .query(UUID.class)
                    .single();

            auditWriter.writeTenantEvent(membershipId, "INVITE_ACCEPTED", "membership", membershipId.toString(),
                    Map.of("status", "INVITED"), Map.of("status", "ACTIVE"), ipAddress, null);
        });
    }

    public List<InvitationView> listInvitations() {
        TenantContext.requireCurrent();
        return tenantJdbc.sql("""
                SELECT id, invite_type, email, phone_e164, role, manager_membership_id,
                       expires_at, accepted_at, created_at
                FROM invitations
                ORDER BY created_at DESC
                LIMIT 50
                """)
                .query(InvitationView.class)
                .list();
    }

    @Transactional
    public MemberSummary updateMember(AuthenticatedActor actor,
                                      UUID targetMembershipId,
                                      UpdateMemberRequest req,
                                      String ipAddress) {
        UUID tenantId = TenantContext.requireCurrent().value();
        MemberSummary before = findMemberByMembershipId(targetMembershipId)
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Member not found"));

        if ("SUPER_ADMIN".equals(before.role()) && !actor.isSuperAdmin()) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "Only SUPER_ADMIN may modify a SUPER_ADMIN member");
        }

        String nextRole = req.role() != null ? req.role().trim().toUpperCase(Locale.ROOT) : before.role();
        if (!List.of("SUPER_ADMIN", "ADMIN", "MANAGER", "EMPLOYEE").contains(nextRole)) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Invalid role: " + nextRole);
        }
        if (("SUPER_ADMIN".equals(nextRole) || "ADMIN".equals(nextRole)) && !actor.isSuperAdmin()) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "Only SUPER_ADMIN may assign ADMIN or SUPER_ADMIN roles");
        }

        String nextStatus = req.status() != null ? req.status().trim().toUpperCase(Locale.ROOT) : before.status();
        if (!List.of("ACTIVE", "SUSPENDED", "OFFBOARDED").contains(nextStatus)) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Invalid status: " + nextStatus);
        }

        if ("SUPER_ADMIN".equals(before.role()) && (!"SUPER_ADMIN".equals(nextRole) || !"ACTIVE".equals(nextStatus))) {
            Integer activeSuperAdmins = tenantJdbc.sql("""
                    SELECT COUNT(1) FROM memberships WHERE role = 'SUPER_ADMIN' AND status = 'ACTIVE'
                    """)
                    .query(Integer.class)
                    .single();
            if (activeSuperAdmins != null && activeSuperAdmins <= 1) {
                throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.VALIDATION_ERROR,
                        "Organization must retain at least one active SUPER_ADMIN");
            }
        }

        UUID nextManager = req.managerMembershipId() != null ? req.managerMembershipId() : before.managerMembershipId();
        if (targetMembershipId.equals(nextManager)) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Member cannot report to themselves");
        }

        tenantJdbc.sql("""
                UPDATE memberships
                SET role = :role,
                    status = :status,
                    manager_membership_id = :mgr,
                    job_title = :title,
                    employee_code = :empCode
                WHERE id = :mid
                """)
                .param("role", nextRole)
                .param("status", nextStatus)
                .param("mgr", nextManager)
                .param("title", req.jobTitle() != null ? trimToNull(req.jobTitle()) : before.jobTitle())
                .param("empCode", req.employeeCode() != null ? trimToNull(req.employeeCode()) : before.employeeCode())
                .param("mid", targetMembershipId)
                .update();

        directoryService.updateUserStatus(tenantId, before.userId(), "ACTIVE".equals(nextStatus) ? "ACTIVE" : "DISABLED");

        MemberSummary after = findMemberByMembershipId(targetMembershipId).orElseThrow();
        auditWriter.writeTenantEvent(actor.membershipId(), "MEMBER_UPDATED", "membership", targetMembershipId.toString(),
                Map.of("role", before.role(), "status", before.status()),
                Map.of("role", after.role(), "status", after.status()),
                ipAddress, null);
        return after;
    }

    private List<MemberSummary> fetchMembersWithClause(String clause, Map<String, Object> params) {
        String sql = """
                SELECT m.id AS membership_id, u.id AS user_id, u.full_name, u.email, u.phone_e164,
                       m.role, m.status, m.manager_membership_id, mgr_u.full_name AS manager_name,
                       m.job_title, m.employee_code
                FROM memberships m
                JOIN users u ON u.id = m.user_id
                LEFT JOIN memberships mgr_m ON mgr_m.id = m.manager_membership_id
                LEFT JOIN users mgr_u ON mgr_u.id = mgr_m.user_id
                """ + " " + clause;

        var query = tenantJdbc.sql(sql);
        for (var e : params.entrySet()) {
            query = query.param(e.getKey(), e.getValue());
        }

        record RawMember(
                UUID membershipId, UUID userId, String fullName, String email, String phoneE164,
                String role, String status, UUID managerMembershipId, String managerName,
                String jobTitle, String employeeCode
        ) {}

        List<RawMember> rows = query.query((rs, rowNum) -> new RawMember(
                rs.getObject("membership_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("phone_e164"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getObject("manager_membership_id", UUID.class),
                rs.getString("manager_name"),
                rs.getString("job_title"),
                rs.getString("employee_code")
        )).list();

        record SiteAssignRow(UUID membershipId, UUID siteId) {}
        List<SiteAssignRow> assignments = tenantJdbc.sql("SELECT membership_id, site_id FROM site_assignments")
                .query(SiteAssignRow.class)
                .list();

        Map<UUID, List<UUID>> sitesByMember = new LinkedHashMap<>();
        for (SiteAssignRow row : assignments) {
            sitesByMember.computeIfAbsent(row.membershipId(), k -> new ArrayList<>()).add(row.siteId());
        }

        return rows.stream()
                .map(r -> new MemberSummary(
                        r.membershipId(), r.userId(), r.fullName(), r.email(), r.phoneE164(),
                        r.role(), r.status(), r.managerMembershipId(), r.managerName(),
                        r.jobTitle(), r.employeeCode(),
                        sitesByMember.getOrDefault(r.membershipId(), List.of())
                ))
                .toList();
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String validateTimezone(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(raw.trim()).getId();
        } catch (Exception e) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Invalid timezone: " + raw);
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private static String trimToNull(String val) {
        if (val == null) return null;
        String trimmed = val.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
