package com.proofly.site;

import com.proofly.common.AuthenticatedActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Sites & Geofences", description = "PostGIS site geofence CRUD, timezone shift rules, and multi-site staff assignments")
public class SiteController {

    public record CreateSitePayload(
            @NotBlank String name,
            String code,
            String address,
            double latitude,
            double longitude,
            Integer radiusMeters,
            String timezone,
            String shiftStartLocal,
            Integer lateGraceMinutes,
            Integer overtimeThresholdMinutes
    ) {}

    public record SetAssignmentsPayload(
            List<SiteService.SiteAssignmentItem> assignments
    ) {}

    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    @GetMapping("/api/v1/sites")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "List visible sites (all for ADMIN+, assigned active sites for MANAGER/EMPLOYEE)")
    public List<SitePublicApi.SiteView> listSites() {
        AuthenticatedActor actor = AuthenticatedActor.requireCurrent();
        return siteService.listVisibleSites(actor.membershipId(), actor.role());
    }

    @PostMapping("/api/v1/sites")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Create a new geofenced work site (ADMIN+)")
    public ResponseEntity<SitePublicApi.SiteView> createSite(
            @Valid @RequestBody CreateSitePayload payload,
            HttpServletRequest httpReq
    ) {
        var created = siteService.createSite(
                AuthenticatedActor.requireCurrent(),
                new SiteService.CreateSiteRequest(
                        payload.name(),
                        payload.code(),
                        payload.address(),
                        payload.latitude(),
                        payload.longitude(),
                        payload.radiusMeters(),
                        payload.timezone(),
                        payload.shiftStartLocal(),
                        payload.lateGraceMinutes(),
                        payload.overtimeThresholdMinutes()
                ),
                clientIp(httpReq)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/api/v1/sites/{siteId}")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Update site geofence radius, coordinates, shift rules, or active state (ADMIN+)")
    public SitePublicApi.SiteView updateSite(
            @PathVariable UUID siteId,
            @RequestBody SiteService.UpdateSiteRequest req,
            HttpServletRequest httpReq
    ) {
        return siteService.updateSite(AuthenticatedActor.requireCurrent(), siteId, req, clientIp(httpReq));
    }

    @GetMapping("/api/v1/sites/assignments/me")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "Get current user's assigned sites with effective per-site shift rules")
    public List<SitePublicApi.SiteAssignmentView> myAssignments() {
        AuthenticatedActor actor = AuthenticatedActor.requireCurrent();
        return siteService.listAssignmentsForMember(actor.membershipId());
    }

    @PutMapping("/api/v1/sites/assignments/{membershipId}")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Set assigned sites and optional shift rule overrides for a staff member (ADMIN+)")
    public List<SitePublicApi.SiteAssignmentView> setMemberAssignments(
            @PathVariable UUID membershipId,
            @RequestBody SetAssignmentsPayload payload,
            HttpServletRequest httpReq
    ) {
        return siteService.setMemberAssignments(
                AuthenticatedActor.requireCurrent(),
                membershipId,
                payload.assignments(),
                clientIp(httpReq)
        );
    }

    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
    }
}
