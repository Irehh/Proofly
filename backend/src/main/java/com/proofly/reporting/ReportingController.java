package com.proofly.reporting;

import com.proofly.common.AuthenticatedActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Live Board & Payroll Reports", description = "Real-time team presence board and payroll-ready CSV export")
public class ReportingController {

    private final ReportingService reportingService;

    public ReportingController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/api/v1/reports/live-board")
    @PreAuthorize("@policyAuth.isManagerOrAbove()")
    @Operation(summary = "Get real-time 'who is on site / late / pending review / on leave / off' board (MANAGER+)")
    public ReportingService.LiveBoardResponse liveBoard(
            @RequestParam(value = "siteId", required = false) UUID siteId
    ) {
        return reportingService.getLiveBoard(AuthenticatedActor.requireCurrent(), siteId);
    }

    @GetMapping("/api/v1/reports/payroll-summary")
    @PreAuthorize("@policyAuth.isManagerOrAbove()")
    @Operation(summary = "Preview payroll regular, late, and overtime hours per member and site (MANAGER+)")
    public List<ReportingService.PayrollRow> payrollSummary(
            @RequestParam("fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam("toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(value = "siteId", required = false) UUID siteId
    ) {
        return reportingService.buildPayrollSummary(AuthenticatedActor.requireCurrent(), fromDate, toDate, siteId);
    }

    @GetMapping("/api/v1/reports/payroll-csv")
    @PreAuthorize("@policyAuth.isManagerOrAbove()")
    @Operation(summary = "Download payroll-ready CSV with regular, late, and overtime hours (MANAGER+)")
    public ResponseEntity<byte[]> payrollCsv(
            @RequestParam("fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam("toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(value = "siteId", required = false) UUID siteId
    ) {
        String csv = reportingService.exportPayrollCsv(AuthenticatedActor.requireCurrent(), fromDate, toDate, siteId);
        String filename = "proofly-payroll-" + fromDate + "-to-" + toDate + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
