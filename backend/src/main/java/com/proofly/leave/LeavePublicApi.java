package com.proofly.leave;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface LeavePublicApi {

    record LeaveRequestView(
            UUID id,
            UUID membershipId,
            String staffName,
            UUID managerMembershipId,
            String managerName,
            String leaveType,
            LocalDate startDate,
            LocalDate endDate,
            String reason,
            String status,
            String reviewedByName,
            String reviewNote,
            Instant reviewedAt,
            Instant createdAt
    ) {}

    Set<UUID> findApprovedLeaveMembershipIdsOnDate(LocalDate date);

    List<LeaveRequestView> listVisibleLeaveRequests(UUID actorMembershipId, String actorRole);
}
