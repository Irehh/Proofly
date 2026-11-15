package com.proofly.attendance;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Attendance & Corrections", description = "Geofenced clock-in/out, flagged out-of-fence review queue, and audited who/when/why corrections")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @PostMapping("/api/v1/attendance/clock-in")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "Record a geofenced clock-in event (flags outside radius as PENDING_REVIEW)")
    public ResponseEntity<AttendancePublicApi.AttendanceEventView> clockIn(
            @RequestBody AttendanceService.ClockRequest req,
            HttpServletRequest httpReq
    ) {
        var created = attendanceService.clockIn(AuthenticatedActor.requireCurrent(), req, clientIp(httpReq));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/api/v1/attendance/clock-out")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "Record a geofenced clock-out event paired with active clock-in")
    public ResponseEntity<AttendancePublicApi.AttendanceEventView> clockOut(
            @RequestBody AttendanceService.ClockRequest req,
            HttpServletRequest httpReq
    ) {
        var created = attendanceService.clockOut(AuthenticatedActor.requireCurrent(), req, clientIp(httpReq));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/v1/attendance/me")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "List personal clock-in/out history for the signed-in user")
    public List<AttendancePublicApi.AttendanceEventView> myAttendance(
            @RequestParam(value = "limit", defaultValue = "40") int limit
    ) {
        AuthenticatedActor actor = AuthenticatedActor.requireCurrent();
        return attendanceService.listMyAttendance(actor.membershipId(), limit);
    }

    @GetMapping("/api/v1/attendance/flagged")
    @PreAuthorize("@policyAuth.isManagerOrAbove()")
    @Operation(summary = "List PENDING_REVIEW out-of-fence clock attempts scoped by role")
    public List<AttendancePublicApi.AttendanceEventView> listFlagged() {
        AuthenticatedActor actor = AuthenticatedActor.requireCurrent();
        return attendanceService.listFlaggedReviews(actor.membershipId(), actor.role());
    }

    @PostMapping("/api/v1/attendance/{eventId}/review")
    @PreAuthorize("@policyAuth.isManagerOrAbove()")
    @Operation(summary = "Approve or reject a flagged out-of-fence clock attempt with required reason")
    public AttendancePublicApi.AttendanceEventView reviewFlagged(
            @PathVariable UUID eventId,
            @RequestBody AttendanceService.ReviewDecisionRequest req,
            HttpServletRequest httpReq
    ) {
        return attendanceService.reviewFlaggedEvent(AuthenticatedActor.requireCurrent(), eventId, req, clientIp(httpReq));
    }

    @GetMapping("/api/v1/attendance/corrections")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "List attendance corrections scoped strictly by caller role")
    public List<AttendancePublicApi.CorrectionView> listCorrections() {
        return attendanceService.listCorrections(AuthenticatedActor.requireCurrent());
    }

    @PostMapping("/api/v1/attendance/corrections")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "Submit an attendance timestamp correction request with mandatory reason")
    public ResponseEntity<AttendancePublicApi.CorrectionView> requestCorrection(
            @RequestBody AttendanceService.CreateCorrectionRequest req,
            HttpServletRequest httpReq
    ) {
        var created = attendanceService.requestCorrection(AuthenticatedActor.requireCurrent(), req, clientIp(httpReq));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/api/v1/attendance/corrections/{correctionId}/review")
    @PreAuthorize("@policyAuth.isManagerOrAbove()")
    @Operation(summary = "Approve or reject a correction with who/when/why audit trail")
    public AttendancePublicApi.CorrectionView reviewCorrection(
            @PathVariable UUID correctionId,
            @RequestBody AttendanceService.ReviewDecisionRequest req,
            HttpServletRequest httpReq
    ) {
        return attendanceService.reviewCorrection(AuthenticatedActor.requireCurrent(), correctionId, req, clientIp(httpReq));
    }

    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
    }
}
