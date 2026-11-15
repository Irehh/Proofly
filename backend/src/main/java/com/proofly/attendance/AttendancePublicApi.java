package com.proofly.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AttendancePublicApi {

    record AttendanceEventView(
            UUID id,
            UUID membershipId,
            String staffName,
            String employeeCode,
            UUID siteId,
            String siteName,
            String eventType,
            UUID pairedClockInId,
            Instant occurredAt,
            Instant effectiveAt,
            LocalDate localDate,
            int distanceMeters,
            int accuracyMeters,
            String geofenceStatus,
            boolean isLate,
            int lateMinutes,
            int workedMinutes,
            int overtimeMinutes,
            String notes,
            String reviewedByName,
            Instant reviewedAt,
            String reviewReason
    ) {}

    record CorrectionView(
            UUID id,
            UUID attendanceEventId,
            UUID membershipId,
            String staffName,
            String eventType,
            String siteName,
            String status,
            Instant originalEffectiveAt,
            Instant proposedEffectiveAt,
            String reason,
            String requestedByName,
            String reviewedByName,
            String reviewNote,
            Instant reviewedAt,
            Instant createdAt
    ) {}

    List<AttendanceEventView> listEventsForDateRange(UUID actorMembershipId,
                                                     String actorRole,
                                                     LocalDate fromDate,
                                                     LocalDate toDate,
                                                     UUID siteId);

    List<AttendanceEventView> listFlaggedReviews(UUID actorMembershipId, String actorRole);
}
