package com.proofly.attendance;

import com.proofly.audit.AuditWriter;
import com.proofly.common.AuthenticatedActor;
import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.notification.NotificationOutboxService;
import com.proofly.organization.OrganizationPublicApi;
import com.proofly.site.SitePublicApi;
import com.proofly.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AttendanceService implements AttendancePublicApi {

    public record ClockRequest(
            UUID siteId,
            double latitude,
            double longitude,
            int accuracyMeters,
            String notes
    ) {}

    public record ReviewDecisionRequest(
            String decision,
            String reason
    ) {}

    public record CreateCorrectionRequest(
            UUID attendanceEventId,
            Instant proposedEffectiveAt,
            String reason
    ) {}

    private final JdbcClient tenantJdbc;
    private final OrganizationPublicApi orgApi;
    private final SitePublicApi siteApi;
    private final AuditWriter auditWriter;
    private final NotificationOutboxService outboxService;

    public AttendanceService(DataSource tenantRoutingDataSource,
                             OrganizationPublicApi orgApi,
                             SitePublicApi siteApi,
                             AuditWriter auditWriter,
                             NotificationOutboxService outboxService) {
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
        this.orgApi = orgApi;
        this.siteApi = siteApi;
        this.auditWriter = auditWriter;
        this.outboxService = outboxService;
    }

    @Transactional
    public AttendanceEventView clockIn(AuthenticatedActor actor, ClockRequest req, String ipAddress) {
        UUID tenantId = TenantContext.requireCurrent().value();
        var member = orgApi.findMemberByMembershipId(actor.membershipId())
                .orElseThrow(() -> new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Active membership required"));
        if (!"ACTIVE".equals(member.status())) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Membership is suspended or offboarded");
        }

        // Prevent duplicate open shift
        var openShift = findLatestOpenClockIn(actor.membershipId());
        if (openShift.isPresent()) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT,
                    "You already have an active clock-in shift. Please clock out before starting another shift.");
        }

        var rule = siteApi.resolveEffectiveRule(actor.membershipId(), req.siteId())
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Active site not found"));

        if (!rule.isAssigned() && !actor.isAdminOrAbove()) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "You are not assigned to site " + rule.siteName());
        }

        var orgSettings = orgApi.getOrganizationSettings();
        int distanceMeters = siteApi.computePostGisDistanceMeters(req.siteId(), req.latitude(), req.longitude());
        int accuracy = Math.max(0, req.accuracyMeters());
        boolean withinFence = distanceMeters <= rule.radiusMeters() && accuracy <= orgSettings.maxGpsAccuracyMeters();
        String geofenceStatus = withinFence ? "VERIFIED" : "PENDING_REVIEW";

        Instant now = Instant.now();
        ZoneId siteZone = ZoneId.of(rule.timezone());
        ZonedDateTime localNow = now.atZone(siteZone);
        LocalDate localDate = localNow.toLocalDate();

        ZonedDateTime graceDeadline = localDate.atTime(rule.shiftStartLocal())
                .atZone(siteZone)
                .plusMinutes(rule.lateGraceMinutes());
        int lateMinutes = localNow.isAfter(graceDeadline)
                ? (int) Math.max(1, Duration.between(graceDeadline, localNow).toMinutes())
                : 0;
        boolean isLate = lateMinutes > 0;

        UUID eventId = UUID.randomUUID();
        tenantJdbc.sql("""
                INSERT INTO attendance_events
                (id, membership_id, site_id, event_type, occurred_at, effective_at, local_date,
                 recorded_lat, recorded_lng, accuracy_meters, distance_meters, geofence_status,
                 is_late, late_minutes, worked_minutes, overtime_minutes, notes)
                VALUES (:id, :mid, :sid, 'CLOCK_IN', :now, :now, :localDate,
                        :lat, :lng, :acc, :dist, :geoStatus,
                        :isLate, :lateMin, 0, 0, :notes)
                """)
                .param("id", eventId)
                .param("mid", actor.membershipId())
                .param("sid", req.siteId())
                .param("now", Timestamp.from(now))
                .param("localDate", Date.valueOf(localDate))
                .param("lat", req.latitude())
                .param("lng", req.longitude())
                .param("acc", accuracy)
                .param("dist", distanceMeters)
                .param("geoStatus", geofenceStatus)
                .param("isLate", isLate)
                .param("lateMin", lateMinutes)
                .param("notes", req.notes() != null ? req.notes().trim() : null)
                .update();

        auditWriter.writeTenantEvent(actor.membershipId(), "CLOCK_IN_RECORDED", "attendance_event", eventId.toString(),
                null, Map.of("siteId", req.siteId().toString(), "distanceMeters", distanceMeters, "status", geofenceStatus),
                ipAddress, null);

        if ("PENDING_REVIEW".equals(geofenceStatus) && member.managerMembershipId() != null) {
            orgApi.findMemberByMembershipId(member.managerMembershipId()).ifPresent(mgr -> {
                if (mgr.email() != null && !mgr.email().isBlank()) {
                    outboxService.enqueueEmail(
                            tenantId,
                            mgr.email(),
                            "ATTENDANCE_OUT_OF_FENCE_FLAGGED",
                            "Out-of-fence clock-in flagged — " + member.fullName(),
                            Map.of(
                                    "staffName", member.fullName(),
                                    "siteName", rule.siteName(),
                                    "distanceMeters", String.valueOf(distanceMeters),
                                    "radiusMeters", String.valueOf(rule.radiusMeters())
                            ),
                            "oof-flag:" + eventId
                    );
                }
            });
        }

        return findEventById(eventId).orElseThrow();
    }

    @Transactional
    public AttendanceEventView clockOut(AuthenticatedActor actor, ClockRequest req, String ipAddress) {
        var openShift = findLatestOpenClockIn(actor.membershipId())
                .orElseThrow(() -> new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT,
                        "No active clock-in shift found to clock out from"));

        UUID siteId = req.siteId() != null ? req.siteId() : openShift.siteId();
        var rule = siteApi.resolveEffectiveRule(actor.membershipId(), siteId)
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Active site not found"));

        var orgSettings = orgApi.getOrganizationSettings();
        int distanceMeters = siteApi.computePostGisDistanceMeters(siteId, req.latitude(), req.longitude());
        int accuracy = Math.max(0, req.accuracyMeters());
        boolean withinFence = distanceMeters <= rule.radiusMeters() && accuracy <= orgSettings.maxGpsAccuracyMeters();
        String geofenceStatus = withinFence ? "VERIFIED" : "PENDING_REVIEW";

        Instant now = Instant.now();
        ZoneId siteZone = ZoneId.of(rule.timezone());
        LocalDate localDate = now.atZone(siteZone).toLocalDate();

        int workedMinutes = (int) Math.max(0, Duration.between(openShift.effectiveAt(), now).toMinutes());
        int overtimeMinutes = Math.max(0, workedMinutes - rule.overtimeThresholdMinutes());

        UUID eventId = UUID.randomUUID();
        tenantJdbc.sql("""
                INSERT INTO attendance_events
                (id, membership_id, site_id, event_type, paired_clock_in_id, occurred_at, effective_at, local_date,
                 recorded_lat, recorded_lng, accuracy_meters, distance_meters, geofence_status,
                 is_late, late_minutes, worked_minutes, overtime_minutes, notes)
                VALUES (:id, :mid, :sid, 'CLOCK_OUT', :pairedId, :now, :now, :localDate,
                        :lat, :lng, :acc, :dist, :geoStatus,
                        FALSE, 0, :workedMin, :otMin, :notes)
                """)
                .param("id", eventId)
                .param("mid", actor.membershipId())
                .param("sid", siteId)
                .param("pairedId", openShift.id())
                .param("now", Timestamp.from(now))
                .param("localDate", Date.valueOf(localDate))
                .param("lat", req.latitude())
                .param("lng", req.longitude())
                .param("acc", accuracy)
                .param("dist", distanceMeters)
                .param("geoStatus", geofenceStatus)
                .param("workedMin", workedMinutes)
                .param("otMin", overtimeMinutes)
                .param("notes", req.notes() != null ? req.notes().trim() : null)
                .update();

        auditWriter.writeTenantEvent(actor.membershipId(), "CLOCK_OUT_RECORDED", "attendance_event", eventId.toString(),
                null, Map.of("siteId", siteId.toString(), "workedMinutes", workedMinutes, "overtimeMinutes", overtimeMinutes),
                ipAddress, null);

        return findEventById(eventId).orElseThrow();
    }

    public List<AttendanceEventView> listMyAttendance(UUID membershipId, int limit) {
        TenantContext.requireCurrent();
        int clamped = Math.min(200, Math.max(1, limit));
        return fetchEvents("WHERE e.membership_id = :mid ORDER BY e.effective_at DESC LIMIT :limit",
                Map.of("mid", membershipId, "limit", clamped));
    }

    @Override
    public List<AttendanceEventView> listEventsForDateRange(UUID actorMembershipId,
                                                            String actorRole,
                                                            LocalDate fromDate,
                                                            LocalDate toDate,
                                                            UUID siteId) {
        TenantContext.requireCurrent();
        StringBuilder where = new StringBuilder("WHERE e.local_date BETWEEN :fromDate AND :toDate ");
        var params = new java.util.HashMap<String, Object>();
        params.put("fromDate", Date.valueOf(fromDate));
        params.put("toDate", Date.valueOf(toDate));

        if (siteId != null) {
            where.append("AND e.site_id = :siteId ");
            params.put("siteId", siteId);
        }
        if ("EMPLOYEE".equals(actorRole)) {
            where.append("AND e.membership_id = :actorMid ");
            params.put("actorMid", actorMembershipId);
        } else if ("MANAGER".equals(actorRole)) {
            where.append("AND (e.membership_id = :actorMid OR m.manager_membership_id = :actorMid) ");
            params.put("actorMid", actorMembershipId);
        }
        where.append("ORDER BY e.effective_at DESC LIMIT 500");
        return fetchEvents(where.toString(), params);
    }

    @Override
    public List<AttendanceEventView> listFlaggedReviews(UUID actorMembershipId, String actorRole) {
        TenantContext.requireCurrent();
        if ("SUPER_ADMIN".equals(actorRole) || "ADMIN".equals(actorRole)) {
            return fetchEvents("WHERE e.geofence_status = 'PENDING_REVIEW' ORDER BY e.occurred_at DESC", Map.of());
        }
        if ("MANAGER".equals(actorRole)) {
            return fetchEvents(
                    "WHERE e.geofence_status = 'PENDING_REVIEW' AND m.manager_membership_id = :mid ORDER BY e.occurred_at DESC",
                    Map.of("mid", actorMembershipId)
            );
        }
        return List.of();
    }

    @Transactional
    public AttendanceEventView reviewFlaggedEvent(AuthenticatedActor actor,
                                                    UUID eventId,
                                                    ReviewDecisionRequest req,
                                                    String ipAddress) {
        if (req.reason() == null || req.reason().trim().length() < 3) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "A written review reason is required for geofence decisions");
        }
        String decision = req.decision() != null ? req.decision().trim().toUpperCase(Locale.ROOT) : "";
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Decision must be APPROVED or REJECTED");
        }

        AttendanceEventView event = findEventById(eventId)
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Attendance event not found"));

        if (!orgApi.canManageTarget(actor.membershipId(), actor.role(), event.membershipId())) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "You may only review flagged attendance events for your assigned team");
        }

        tenantJdbc.sql("""
                UPDATE attendance_events
                SET geofence_status = :status,
                    reviewed_by_membership_id = :reviewer,
                    reviewed_at = NOW(),
                    review_reason = :reason
                WHERE id = :id AND geofence_status = 'PENDING_REVIEW'
                """)
                .param("status", decision)
                .param("reviewer", actor.membershipId())
                .param("reason", req.reason().trim())
                .param("id", eventId)
                .update();

        auditWriter.writeTenantEvent(actor.membershipId(), "ATTENDANCE_GEOFENCE_REVIEWED", "attendance_event",
                eventId.toString(),
                Map.of("geofenceStatus", event.geofenceStatus()),
                Map.of("geofenceStatus", decision, "reason", req.reason().trim()),
                ipAddress, null);

        return findEventById(eventId).orElseThrow();
    }

    @Transactional
    public CorrectionView requestCorrection(AuthenticatedActor actor, CreateCorrectionRequest req, String ipAddress) {
        if (req.reason() == null || req.reason().trim().length() < 4) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Correction reason (who/when/why) must be at least 4 characters");
        }
        if (req.proposedEffectiveAt() == null) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Proposed effective timestamp is required");
        }

        AttendanceEventView targetEvent = findEventById(req.attendanceEventId())
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Attendance event not found"));

        boolean isSelf = actor.membershipId().equals(targetEvent.membershipId());
        if (!isSelf && !orgApi.canManageTarget(actor.membershipId(), actor.role(), targetEvent.membershipId())) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "You do not have permission to request a correction for this shift event");
        }

        UUID correctionId = UUID.randomUUID();
        tenantJdbc.sql("""
                INSERT INTO attendance_corrections
                (id, attendance_event_id, membership_id, requested_by_membership_id, status,
                 original_effective_at, proposed_effective_at, reason)
                VALUES (:id, :eventId, :mid, :reqMid, 'PENDING', :origAt, :propAt, :reason)
                """)
                .param("id", correctionId)
                .param("eventId", targetEvent.id())
                .param("mid", targetEvent.membershipId())
                .param("reqMid", actor.membershipId())
                .param("origAt", Timestamp.from(targetEvent.effectiveAt()))
                .param("propAt", Timestamp.from(req.proposedEffectiveAt()))
                .param("reason", req.reason().trim())
                .update();

        auditWriter.writeTenantEvent(actor.membershipId(), "CORRECTION_REQUESTED", "attendance_correction",
                correctionId.toString(),
                Map.of("originalEffectiveAt", targetEvent.effectiveAt().toString()),
                Map.of("proposedEffectiveAt", req.proposedEffectiveAt().toString(), "reason", req.reason().trim()),
                ipAddress, null);

        return findCorrectionById(correctionId).orElseThrow();
    }

    public List<CorrectionView> listCorrections(AuthenticatedActor actor) {
        TenantContext.requireCurrent();
        if (actor.isAdminOrAbove()) {
            return fetchCorrections("ORDER BY c.created_at DESC LIMIT 100", Map.of());
        }
        if (actor.isManagerOrAbove()) {
            return fetchCorrections(
                    "WHERE c.membership_id = :mid OR m.manager_membership_id = :mid ORDER BY c.created_at DESC LIMIT 100",
                    Map.of("mid", actor.membershipId())
            );
        }
        return fetchCorrections("WHERE c.membership_id = :mid ORDER BY c.created_at DESC LIMIT 100",
                Map.of("mid", actor.membershipId()));
    }

    @Transactional
    public CorrectionView reviewCorrection(AuthenticatedActor actor,
                                           UUID correctionId,
                                           ReviewDecisionRequest req,
                                           String ipAddress) {
        UUID tenantId = TenantContext.requireCurrent().value();
        if (req.reason() == null || req.reason().trim().length() < 3) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "A written note explaining why is required when approving or rejecting corrections");
        }
        String decision = req.decision() != null ? req.decision().trim().toUpperCase(Locale.ROOT) : "";
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Decision must be APPROVED or REJECTED");
        }

        CorrectionView correction = findCorrectionById(correctionId)
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Correction not found"));

        if (!"PENDING".equals(correction.status())) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT, "Correction has already been decided");
        }
        if (!orgApi.canManageTarget(actor.membershipId(), actor.role(), correction.membershipId())) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "You may only review attendance corrections for your direct team");
        }

        tenantJdbc.sql("""
                UPDATE attendance_corrections
                SET status = :status,
                    reviewed_by_membership_id = :reviewer,
                    review_note = :note,
                    reviewed_at = NOW()
                WHERE id = :id
                """)
                .param("status", decision)
                .param("reviewer", actor.membershipId())
                .param("note", req.reason().trim())
                .param("id", correctionId)
                .update();

        if ("APPROVED".equals(decision)) {
            AttendanceEventView event = findEventById(correction.attendanceEventId()).orElseThrow();
            var rule = siteApi.resolveEffectiveRule(event.membershipId(), event.siteId()).orElseThrow();
            ZoneId siteZone = ZoneId.of(rule.timezone());
            Instant nextEffective = correction.proposedEffectiveAt();
            ZonedDateTime nextLocal = nextEffective.atZone(siteZone);
            LocalDate nextLocalDate = nextLocal.toLocalDate();

            if ("CLOCK_IN".equals(event.eventType())) {
                ZonedDateTime graceDeadline = nextLocalDate.atTime(rule.shiftStartLocal())
                        .atZone(siteZone)
                        .plusMinutes(rule.lateGraceMinutes());
                int lateMin = nextLocal.isAfter(graceDeadline)
                        ? (int) Math.max(1, Duration.between(graceDeadline, nextLocal).toMinutes())
                        : 0;
                tenantJdbc.sql("""
                        UPDATE attendance_events
                        SET effective_at = :eff,
                            local_date = :localDate,
                            is_late = :isLate,
                            late_minutes = :lateMin
                        WHERE id = :eid
                        """)
                        .param("eff", Timestamp.from(nextEffective))
                        .param("localDate", Date.valueOf(nextLocalDate))
                        .param("isLate", lateMin > 0)
                        .param("lateMin", lateMin)
                        .param("eid", event.id())
                        .update();
            } else {
                Instant pairedIn = event.pairedClockInId() != null
                        ? findEventById(event.pairedClockInId()).map(AttendanceEventView::effectiveAt).orElse(nextEffective)
                        : nextEffective;
                int workedMin = (int) Math.max(0, Duration.between(pairedIn, nextEffective).toMinutes());
                int otMin = Math.max(0, workedMin - rule.overtimeThresholdMinutes());
                tenantJdbc.sql("""
                        UPDATE attendance_events
                        SET effective_at = :eff,
                            local_date = :localDate,
                            worked_minutes = :worked,
                            overtime_minutes = :ot
                        WHERE id = :eid
                        """)
                        .param("eff", Timestamp.from(nextEffective))
                        .param("localDate", Date.valueOf(nextLocalDate))
                        .param("worked", workedMin)
                        .param("ot", otMin)
                        .param("eid", event.id())
                        .update();
            }
        }

        auditWriter.writeTenantEvent(actor.membershipId(), "CORRECTION_DECIDED", "attendance_correction",
                correctionId.toString(),
                Map.of("status", "PENDING", "originalEffectiveAt", correction.originalEffectiveAt().toString()),
                Map.of("status", decision, "proposedEffectiveAt", correction.proposedEffectiveAt().toString(),
                        "reviewNote", req.reason().trim(), "requestReason", correction.reason()),
                ipAddress, null);

        orgApi.findMemberByMembershipId(correction.membershipId()).ifPresent(member -> {
            if (member.email() != null && !member.email().isBlank()) {
                outboxService.enqueueEmail(
                        tenantId,
                        member.email(),
                        "CORRECTION_DECIDED",
                        "Attendance correction " + decision.toLowerCase(Locale.ROOT),
                        Map.of("decision", decision, "note", req.reason().trim()),
                        "corr-decided:" + correctionId
                );
            }
        });

        return findCorrectionById(correctionId).orElseThrow();
    }

    private java.util.Optional<AttendanceEventView> findLatestOpenClockIn(UUID membershipId) {
        return fetchEvents("""
                WHERE e.membership_id = :mid
                  AND e.event_type = 'CLOCK_IN'
                  AND e.geofence_status <> 'REJECTED'
                  AND NOT EXISTS (
                      SELECT 1 FROM attendance_events out_ev
                      WHERE out_ev.paired_clock_in_id = e.id
                  )
                ORDER BY e.effective_at DESC
                LIMIT 1
                """, Map.of("mid", membershipId)).stream().findFirst();
    }

    private java.util.Optional<AttendanceEventView> findEventById(UUID eventId) {
        return fetchEvents("WHERE e.id = :id", Map.of("id", eventId)).stream().findFirst();
    }

    private java.util.Optional<CorrectionView> findCorrectionById(UUID correctionId) {
        return fetchCorrections("WHERE c.id = :id", Map.of("id", correctionId)).stream().findFirst();
    }

    private List<AttendanceEventView> fetchEvents(String whereAndOrder, Map<String, Object> params) {
        String sql = """
                SELECT e.id, e.membership_id, u.full_name AS staff_name, m.employee_code,
                       e.site_id, s.name AS site_name, e.event_type, e.paired_clock_in_id,
                       e.occurred_at, e.effective_at, e.local_date, e.distance_meters, e.accuracy_meters,
                       e.geofence_status, e.is_late, e.late_minutes, e.worked_minutes, e.overtime_minutes,
                       e.notes, rev_u.full_name AS reviewed_by_name, e.reviewed_at, e.review_reason
                FROM attendance_events e
                JOIN memberships m ON m.id = e.membership_id
                JOIN users u ON u.id = m.user_id
                JOIN sites s ON s.id = e.site_id
                LEFT JOIN memberships rev_m ON rev_m.id = e.reviewed_by_membership_id
                LEFT JOIN users rev_u ON rev_u.id = rev_m.user_id
                """ + " " + whereAndOrder;

        var query = tenantJdbc.sql(sql);
        for (var e : params.entrySet()) {
            query = query.param(e.getKey(), e.getValue());
        }
        return query.query((rs, rowNum) -> new AttendanceEventView(
                rs.getObject("id", UUID.class),
                rs.getObject("membership_id", UUID.class),
                rs.getString("staff_name"),
                rs.getString("employee_code"),
                rs.getObject("site_id", UUID.class),
                rs.getString("site_name"),
                rs.getString("event_type"),
                rs.getObject("paired_clock_in_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("effective_at").toInstant(),
                rs.getDate("local_date").toLocalDate(),
                rs.getInt("distance_meters"),
                rs.getInt("accuracy_meters"),
                rs.getString("geofence_status"),
                rs.getBoolean("is_late"),
                rs.getInt("late_minutes"),
                rs.getInt("worked_minutes"),
                rs.getInt("overtime_minutes"),
                rs.getString("notes"),
                rs.getString("reviewed_by_name"),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getString("review_reason")
        )).list();
    }

    private List<CorrectionView> fetchCorrections(String whereAndOrder, Map<String, Object> params) {
        String sql = """
                SELECT c.id, c.attendance_event_id, c.membership_id, staff_u.full_name AS staff_name,
                       e.event_type, s.name AS site_name, c.status, c.original_effective_at,
                       c.proposed_effective_at, c.reason, req_u.full_name AS requested_by_name,
                       rev_u.full_name AS reviewed_by_name, c.review_note, c.reviewed_at, c.created_at
                FROM attendance_corrections c
                JOIN attendance_events e ON e.id = c.attendance_event_id
                JOIN sites s ON s.id = e.site_id
                JOIN memberships m ON m.id = c.membership_id
                JOIN users staff_u ON staff_u.id = m.user_id
                JOIN memberships req_m ON req_m.id = c.requested_by_membership_id
                JOIN users req_u ON req_u.id = req_m.user_id
                LEFT JOIN memberships rev_m ON rev_m.id = c.reviewed_by_membership_id
                LEFT JOIN users rev_u ON rev_u.id = rev_m.user_id
                """ + " " + whereAndOrder;

        var query = tenantJdbc.sql(sql);
        for (var e : params.entrySet()) {
            query = query.param(e.getKey(), e.getValue());
        }
        return query.query((rs, rowNum) -> new CorrectionView(
                rs.getObject("id", UUID.class),
                rs.getObject("attendance_event_id", UUID.class),
                rs.getObject("membership_id", UUID.class),
                rs.getString("staff_name"),
                rs.getString("event_type"),
                rs.getString("site_name"),
                rs.getString("status"),
                rs.getTimestamp("original_effective_at").toInstant(),
                rs.getTimestamp("proposed_effective_at").toInstant(),
                rs.getString("reason"),
                rs.getString("requested_by_name"),
                rs.getString("reviewed_by_name"),
                rs.getString("review_note"),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getTimestamp("created_at").toInstant()
        )).list();
    }
}
