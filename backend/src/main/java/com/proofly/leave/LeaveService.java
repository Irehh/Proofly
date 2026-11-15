package com.proofly.leave;

import com.proofly.audit.AuditWriter;
import com.proofly.common.AuthenticatedActor;
import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.notification.NotificationOutboxService;
import com.proofly.organization.OrganizationPublicApi;
import com.proofly.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LeaveService implements LeavePublicApi {

    public record CreateLeaveRequest(
            String leaveType,
            LocalDate startDate,
            LocalDate endDate,
            String reason
    ) {}

    public record LeaveDecisionRequest(
            String decision,
            String note
    ) {}

    private final JdbcClient tenantJdbc;
    private final OrganizationPublicApi orgApi;
    private final AuditWriter auditWriter;
    private final NotificationOutboxService outboxService;

    public LeaveService(DataSource tenantRoutingDataSource,
                        OrganizationPublicApi orgApi,
                        AuditWriter auditWriter,
                        NotificationOutboxService outboxService) {
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
        this.orgApi = orgApi;
        this.auditWriter = auditWriter;
        this.outboxService = outboxService;
    }

    @Transactional
    public LeaveRequestView submitLeave(AuthenticatedActor actor, CreateLeaveRequest req, String ipAddress) {
        UUID tenantId = TenantContext.requireCurrent().value();
        if (req.startDate() == null || req.endDate() == null || req.endDate().isBefore(req.startDate())) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Leave end date must be on or after start date");
        }
        if (req.reason() == null || req.reason().trim().length() < 4) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Please enter a brief leave reason (minimum 4 characters)");
        }
        String leaveType = req.leaveType() != null ? req.leaveType().trim().toUpperCase(Locale.ROOT) : "ANNUAL";
        if (!List.of("ANNUAL", "SICK", "UNPAID", "OTHER").contains(leaveType)) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Invalid leave type: " + leaveType);
        }

        var member = orgApi.findMemberByMembershipId(actor.membershipId())
                .orElseThrow(() -> new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Active membership required"));

        UUID leaveId = UUID.randomUUID();
        tenantJdbc.sql("""
                INSERT INTO leave_requests
                (id, membership_id, manager_membership_id, leave_type, start_date, end_date, reason, status)
                VALUES (:id, :mid, :mgrMid, :type, :start, :end, :reason, 'PENDING')
                """)
                .param("id", leaveId)
                .param("mid", actor.membershipId())
                .param("mgrMid", member.managerMembershipId())
                .param("type", leaveType)
                .param("start", Date.valueOf(req.startDate()))
                .param("end", Date.valueOf(req.endDate()))
                .param("reason", req.reason().trim())
                .update();

        auditWriter.writeTenantEvent(actor.membershipId(), "LEAVE_REQUEST_SUBMITTED", "leave_request",
                leaveId.toString(), null,
                Map.of("leaveType", leaveType, "startDate", req.startDate().toString(), "endDate", req.endDate().toString()),
                ipAddress, null);

        if (member.managerMembershipId() != null) {
            orgApi.findMemberByMembershipId(member.managerMembershipId()).ifPresent(mgr -> {
                if (mgr.email() != null && !mgr.email().isBlank()) {
                    outboxService.enqueueEmail(
                            tenantId,
                            mgr.email(),
                            "LEAVE_REQUEST_SUBMITTED",
                            "Leave request pending — " + member.fullName(),
                            Map.of(
                                    "staffName", member.fullName(),
                                    "leaveType", leaveType,
                                    "dateRange", req.startDate() + " to " + req.endDate()
                            ),
                            "leave-submit:" + leaveId
                    );
                }
            });
        }

        return findLeaveById(leaveId).orElseThrow();
    }

    @Override
    public List<LeaveRequestView> listVisibleLeaveRequests(UUID actorMembershipId, String actorRole) {
        TenantContext.requireCurrent();
        if ("SUPER_ADMIN".equals(actorRole) || "ADMIN".equals(actorRole)) {
            return fetchLeaves("ORDER BY l.created_at DESC LIMIT 100", Map.of());
        }
        if ("MANAGER".equals(actorRole)) {
            return fetchLeaves(
                    "WHERE l.membership_id = :mid OR l.manager_membership_id = :mid ORDER BY l.created_at DESC LIMIT 100",
                    Map.of("mid", actorMembershipId)
            );
        }
        return fetchLeaves("WHERE l.membership_id = :mid ORDER BY l.created_at DESC LIMIT 100",
                Map.of("mid", actorMembershipId));
    }

    @Transactional
    public LeaveRequestView reviewLeave(AuthenticatedActor actor,
                                        UUID leaveId,
                                        LeaveDecisionRequest req,
                                        String ipAddress) {
        UUID tenantId = TenantContext.requireCurrent().value();
        String decision = req.decision() != null ? req.decision().trim().toUpperCase(Locale.ROOT) : "";
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Decision must be APPROVED or REJECTED");
        }

        LeaveRequestView existing = findLeaveById(leaveId)
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Leave request not found"));

        if (!"PENDING".equals(existing.status())) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT, "Leave request already decided");
        }
        if (!orgApi.canManageTarget(actor.membershipId(), actor.role(), existing.membershipId())) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "You may only review leave requests for staff assigned to your team");
        }

        String note = req.note() != null ? req.note().trim() : "";
        tenantJdbc.sql("""
                UPDATE leave_requests
                SET status = :status,
                    reviewed_by_membership_id = :reviewer,
                    review_note = :note,
                    reviewed_at = NOW()
                WHERE id = :id
                """)
                .param("status", decision)
                .param("reviewer", actor.membershipId())
                .param("note", note)
                .param("id", leaveId)
                .update();

        auditWriter.writeTenantEvent(actor.membershipId(), "LEAVE_REQUEST_DECIDED", "leave_request",
                leaveId.toString(),
                Map.of("status", "PENDING"),
                Map.of("status", decision, "note", note),
                ipAddress, null);

        orgApi.findMemberByMembershipId(existing.membershipId()).ifPresent(member -> {
            if (member.email() != null && !member.email().isBlank()) {
                outboxService.enqueueEmail(
                        tenantId,
                        member.email(),
                        "LEAVE_REQUEST_DECIDED",
                        "Leave request " + decision.toLowerCase(Locale.ROOT),
                        Map.of(
                                "leaveType", existing.leaveType(),
                                "decision", decision,
                                "dateRange", existing.startDate() + " to " + existing.endDate(),
                                "note", note
                        ),
                        "leave-decided:" + leaveId
                );
            }
        });

        return findLeaveById(leaveId).orElseThrow();
    }

    @Transactional
    public LeaveRequestView cancelLeave(AuthenticatedActor actor, UUID leaveId, String ipAddress) {
        LeaveRequestView existing = findLeaveById(leaveId)
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Leave request not found"));

        if (!actor.membershipId().equals(existing.membershipId())) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "You can only cancel your own leave requests");
        }
        if (!"PENDING".equals(existing.status())) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT, "Only PENDING leave requests can be canceled");
        }

        tenantJdbc.sql("UPDATE leave_requests SET status = 'CANCELED', reviewed_at = NOW() WHERE id = :id")
                .param("id", leaveId)
                .update();

        auditWriter.writeTenantEvent(actor.membershipId(), "LEAVE_REQUEST_CANCELED", "leave_request",
                leaveId.toString(), Map.of("status", "PENDING"), Map.of("status", "CANCELED"), ipAddress, null);

        return findLeaveById(leaveId).orElseThrow();
    }

    @Override
    public Set<UUID> findApprovedLeaveMembershipIdsOnDate(LocalDate date) {
        TenantContext.requireCurrent();
        List<UUID> ids = tenantJdbc.sql("""
                SELECT DISTINCT membership_id
                FROM leave_requests
                WHERE status = 'APPROVED' AND :d BETWEEN start_date AND end_date
                """)
                .param("d", Date.valueOf(date))
                .query(UUID.class)
                .list();
        return new HashSet<>(ids);
    }

    private java.util.Optional<LeaveRequestView> findLeaveById(UUID leaveId) {
        return fetchLeaves("WHERE l.id = :id", Map.of("id", leaveId)).stream().findFirst();
    }

    private List<LeaveRequestView> fetchLeaves(String whereAndOrder, Map<String, Object> params) {
        String sql = """
                SELECT l.id, l.membership_id, u.full_name AS staff_name,
                       l.manager_membership_id, mgr_u.full_name AS manager_name,
                       l.leave_type, l.start_date, l.end_date, l.reason, l.status,
                       rev_u.full_name AS reviewed_by_name, l.review_note, l.reviewed_at, l.created_at
                FROM leave_requests l
                JOIN memberships m ON m.id = l.membership_id
                JOIN users u ON u.id = m.user_id
                LEFT JOIN memberships mgr_m ON mgr_m.id = l.manager_membership_id
                LEFT JOIN users mgr_u ON mgr_u.id = mgr_m.user_id
                LEFT JOIN memberships rev_m ON rev_m.id = l.reviewed_by_membership_id
                LEFT JOIN users rev_u ON rev_u.id = rev_m.user_id
                """ + " " + whereAndOrder;

        var query = tenantJdbc.sql(sql);
        for (var e : params.entrySet()) {
            query = query.param(e.getKey(), e.getValue());
        }
        return query.query((rs, rowNum) -> new LeaveRequestView(
                rs.getObject("id", UUID.class),
                rs.getObject("membership_id", UUID.class),
                rs.getString("staff_name"),
                rs.getObject("manager_membership_id", UUID.class),
                rs.getString("manager_name"),
                rs.getString("leave_type"),
                rs.getDate("start_date").toLocalDate(),
                rs.getDate("end_date").toLocalDate(),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("reviewed_by_name"),
                rs.getString("review_note"),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getTimestamp("created_at").toInstant()
        )).list();
    }
}
