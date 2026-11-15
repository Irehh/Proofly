package com.proofly.site;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SitePublicApi {

    record SiteView(
            UUID id,
            String name,
            String code,
            String address,
            double latitude,
            double longitude,
            int radiusMeters,
            String timezone,
            String shiftStartLocal,
            int lateGraceMinutes,
            int overtimeThresholdMinutes,
            boolean isActive,
            int assignedStaffCount
    ) {}

    record SiteAssignmentView(
            UUID id,
            UUID membershipId,
            UUID siteId,
            String siteName,
            String siteCode,
            String timezone,
            boolean isPrimary,
            String shiftStartLocal,
            int lateGraceMinutes,
            int overtimeThresholdMinutes
    ) {}

    record EffectiveSiteRule(
            UUID siteId,
            String siteName,
            int radiusMeters,
            String timezone,
            LocalTime shiftStartLocal,
            int lateGraceMinutes,
            int overtimeThresholdMinutes,
            boolean isAssigned
    ) {}

    List<SiteView> listVisibleSites(UUID actorMembershipId, String actorRole);

    Optional<EffectiveSiteRule> resolveEffectiveRule(UUID membershipId, UUID siteId);

    int computePostGisDistanceMeters(UUID siteId, double latitude, double longitude);
}
