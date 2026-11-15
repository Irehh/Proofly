package com.proofly.leave;

import com.proofly.common.AuthenticatedActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Leave Management", description = "Staff leave submission routed to assigned manager with approval/rejection workflow")
public class LeaveController {

    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @PostMapping("/api/v1/leave/requests")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "Submit a leave request routed to the assigned manager")
    public ResponseEntity<LeavePublicApi.LeaveRequestView> submitLeave(
            @RequestBody LeaveService.CreateLeaveRequest req,
            HttpServletRequest httpReq
    ) {
        var created = leaveService.submitLeave(AuthenticatedActor.requireCurrent(), req, clientIp(httpReq));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/v1/leave/requests")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "List leave requests visible to current user (self for EMPLOYEE, team for MANAGER, org for ADMIN+)")
    public List<LeavePublicApi.LeaveRequestView> listVisibleLeaves() {
        AuthenticatedActor actor = AuthenticatedActor.requireCurrent();
        return leaveService.listVisibleLeaveRequests(actor.membershipId(), actor.role());
    }

    @PostMapping("/api/v1/leave/requests/{leaveId}/review")
    @PreAuthorize("@policyAuth.isManagerOrAbove()")
    @Operation(summary = "Approve or reject a team member's pending leave request (MANAGER+)")
    public LeavePublicApi.LeaveRequestView reviewLeave(
            @PathVariable UUID leaveId,
            @RequestBody LeaveService.LeaveDecisionRequest req,
            HttpServletRequest httpReq
    ) {
        return leaveService.reviewLeave(AuthenticatedActor.requireCurrent(), leaveId, req, clientIp(httpReq));
    }

    @PostMapping("/api/v1/leave/requests/{leaveId}/cancel")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "Cancel one's own PENDING leave request")
    public LeavePublicApi.LeaveRequestView cancelLeave(
            @PathVariable UUID leaveId,
            HttpServletRequest httpReq
    ) {
        return leaveService.cancelLeave(AuthenticatedActor.requireCurrent(), leaveId, clientIp(httpReq));
    }

    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
    }
}
