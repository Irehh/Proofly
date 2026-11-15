package com.proofly.organization;

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
@Tag(name = "Organization & Staff", description = "Organization rules, role-scoped staff roster, email & phone-PIN invites, and member governance")
public class OrganizationController {

    public record InvitePayload(
            String inviteType,
            @NotBlank String fullName,
            String email,
            String phoneE164,
            String initialPin,
            String role,
            UUID managerMembershipId,
            String jobTitle,
            String employeeCode
    ) {}

    private final OrganizationService orgService;

    public OrganizationController(OrganizationService orgService) {
        this.orgService = orgService;
    }

    @GetMapping("/api/v1/org/settings")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "Get organization attendance and geofence defaults")
    public OrganizationPublicApi.OrgSettingsView getSettings() {
        return orgService.getOrganizationSettings();
    }

    @PutMapping("/api/v1/org/settings")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Update organization timezone, late grace, and overtime defaults (ADMIN+)")
    public OrganizationPublicApi.OrgSettingsView updateSettings(
            @RequestBody OrganizationService.UpdateOrgSettingsRequest req,
            HttpServletRequest httpReq
    ) {
        return orgService.updateOrganizationSettings(AuthenticatedActor.requireCurrent(), req, clientIp(httpReq));
    }

    @GetMapping("/api/v1/org/members")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "List visible staff members (scoped strictly by caller role)")
    public List<OrganizationPublicApi.MemberSummary> listMembers() {
        AuthenticatedActor actor = AuthenticatedActor.requireCurrent();
        return orgService.listVisibleMembers(actor.membershipId(), actor.role());
    }

    @PostMapping("/api/v1/org/invites")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Invite staff by work email or onboard immediately with E.164 Phone + PIN (ADMIN+)")
    public ResponseEntity<OrganizationPublicApi.MemberSummary> inviteStaff(
            @Valid @RequestBody InvitePayload payload,
            HttpServletRequest httpReq
    ) {
        var created = orgService.inviteStaff(
                AuthenticatedActor.requireCurrent(),
                new OrganizationService.InviteStaffRequest(
                        payload.inviteType(),
                        payload.fullName(),
                        payload.email(),
                        payload.phoneE164(),
                        payload.initialPin(),
                        payload.role(),
                        payload.managerMembershipId(),
                        payload.jobTitle(),
                        payload.employeeCode()
                ),
                clientIp(httpReq)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/v1/org/invites")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "List email staff invitations and acceptance status (ADMIN+)")
    public List<OrganizationService.InvitationView> listInvitations() {
        return orgService.listInvitations();
    }

    @PostMapping("/api/v1/org/invites/accept")
    @Operation(summary = "Accept a staff email invitation and set initial account password")
    public ResponseEntity<Void> acceptInvite(
            @RequestBody OrganizationService.AcceptInviteRequest req,
            HttpServletRequest httpReq
    ) {
        orgService.acceptEmailInvite(req, clientIp(httpReq));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/api/v1/org/members/{membershipId}")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Update member role, manager assignment, or active/suspended/offboarded status (ADMIN+ / SUPER_ADMIN)")
    public OrganizationPublicApi.MemberSummary updateMember(
            @PathVariable UUID membershipId,
            @RequestBody OrganizationService.UpdateMemberRequest req,
            HttpServletRequest httpReq
    ) {
        return orgService.updateMember(AuthenticatedActor.requireCurrent(), membershipId, req, clientIp(httpReq));
    }

    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
    }
}
