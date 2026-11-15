package com.proofly.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationPublicApi {

    record OrgSettingsView(
            String orgName,
            String defaultTimezone,
            int lateGraceMinutes,
            int overtimeThresholdMinutes,
            int maxGpsAccuracyMeters,
            int locationRetentionDays
    ) {}

    record MemberSummary(
            UUID membershipId,
            UUID userId,
            String fullName,
            String email,
            String phoneE164,
            String role,
            String status,
            UUID managerMembershipId,
            String managerName,
            String jobTitle,
            String employeeCode,
            List<UUID> assignedSiteIds
    ) {}

    OrgSettingsView getOrganizationSettings();

    Optional<MemberSummary> findMemberByMembershipId(UUID membershipId);

    List<MemberSummary> listVisibleMembers(UUID actorMembershipId, String actorRole);

    boolean canManageTarget(UUID actorMembershipId, String actorRole, UUID targetMembershipId);
}
