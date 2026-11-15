package com.proofly.reporting;

import com.proofly.attendance.AttendancePublicApi;
import com.proofly.common.AuthenticatedActor;
import com.proofly.leave.LeavePublicApi;
import com.proofly.organization.OrganizationPublicApi;
import com.proofly.site.SitePublicApi;
import com.proofly.tenancy.TenantContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReportingService {

    public record LiveBoardSummary(
            int totalVisibleStaff,
            int onSiteCount,
            int lateCount,
            int pendingReviewCount,
            int onLeaveCount,
            int offCount
    ) {}

    public record LiveStaffRow(
            UUID membershipId,
            String fullName,
            String employeeCode,
            String role,
            String presenceStatus,
            UUID siteId,
            String siteName,
            Instant lastEventAt,
            boolean isLate,
            int lateMinutes,
            int distanceMeters,
            String geofenceStatus
    ) {}

    public record LiveBoardResponse(
            LocalDate date,
            LiveBoardSummary summary,
            List<LiveStaffRow> rows
    ) {}

    public record PayrollRow(
            UUID membershipId,
            String fullName,
            String employeeCode,
            String role,
            String siteName,
            int shiftsCompleted,
            int lateArrivals,
            int totalLateMinutes,
            int regularMinutes,
            int overtimeMinutes,
            int totalWorkedMinutes,
            int pendingReviewCount
    ) {}

    private final OrganizationPublicApi orgApi;
    private final SitePublicApi siteApi;
    private final AttendancePublicApi attendanceApi;
    private final LeavePublicApi leaveApi;

    public ReportingService(OrganizationPublicApi orgApi,
                            SitePublicApi siteApi,
                            AttendancePublicApi attendanceApi,
                            LeavePublicApi leaveApi) {
        this.orgApi = orgApi;
        this.siteApi = siteApi;
        this.attendanceApi = attendanceApi;
        this.leaveApi = leaveApi;
    }

    public LiveBoardResponse getLiveBoard(AuthenticatedActor actor, UUID siteIdFilter) {
        TenantContext.requireCurrent();
        var orgSettings = orgApi.getOrganizationSettings();
        LocalDate today = LocalDate.now(ZoneId.of(orgSettings.defaultTimezone()));

        List<OrganizationPublicApi.MemberSummary> members = orgApi.listVisibleMembers(actor.membershipId(), actor.role())
                .stream()
                .filter(m -> "ACTIVE".equals(m.status()))
                .filter(m -> siteIdFilter == null || m.assignedSiteIds().contains(siteIdFilter))
                .toList();

        List<SitePublicApi.SiteView> sites = siteApi.listVisibleSites(actor.membershipId(), actor.role());
        Map<UUID, String> siteNames = new LinkedHashMap<>();
        sites.forEach(s -> siteNames.put(s.id(), s.name()));

        List<AttendancePublicApi.AttendanceEventView> todayEvents = attendanceApi.listEventsForDateRange(
                actor.membershipId(), actor.role(), today.minusDays(1), today.plusDays(1), siteIdFilter);

        Map<UUID, List<AttendancePublicApi.AttendanceEventView>> eventsByMember = new LinkedHashMap<>();
        for (var ev : todayEvents) {
            if (ev.localDate().equals(today)) {
                eventsByMember.computeIfAbsent(ev.membershipId(), k -> new ArrayList<>()).add(ev);
            }
        }

        Set<UUID> onLeaveToday = leaveApi.findApprovedLeaveMembershipIdsOnDate(today);

        int onSite = 0;
        int late = 0;
        int pendingReview = 0;
        int onLeave = 0;
        int off = 0;
        List<LiveStaffRow> rows = new ArrayList<>();

        for (var member : members) {
            List<AttendancePublicApi.AttendanceEventView> memberEvents = eventsByMember.getOrDefault(member.membershipId(), List.of())
                    .stream()
                    .sorted(Comparator.comparing(AttendancePublicApi.AttendanceEventView::effectiveAt).reversed())
                    .toList();

            var latest = memberEvents.isEmpty() ? null : memberEvents.getFirst();
            var clockInToday = memberEvents.stream()
                    .filter(e -> "CLOCK_IN".equals(e.eventType()) && !"REJECTED".equals(e.geofenceStatus()))
                    .findFirst()
                    .orElse(null);

            String presenceStatus;
            boolean isLate = clockInToday != null && clockInToday.isLate();
            int lateMinutes = clockInToday != null ? clockInToday.lateMinutes() : 0;

            if (latest != null && "PENDING_REVIEW".equals(latest.geofenceStatus())) {
                presenceStatus = "PENDING_REVIEW";
                pendingReview++;
                if ("CLOCK_IN".equals(latest.eventType())) {
                    onSite++;
                }
                if (isLate) late++;
            } else if (latest != null && "CLOCK_IN".equals(latest.eventType()) && !"REJECTED".equals(latest.geofenceStatus())) {
                presenceStatus = isLate ? "LATE" : "ON_SITE";
                onSite++;
                if (isLate) late++;
            } else if (latest != null && "CLOCK_OUT".equals(latest.eventType())) {
                presenceStatus = "CLOCKED_OUT";
                if (isLate) late++;
            } else if (onLeaveToday.contains(member.membershipId())) {
                presenceStatus = "ON_LEAVE";
                onLeave++;
            } else {
                presenceStatus = "OFF";
                off++;
            }

            UUID resolvedSiteId = latest != null ? latest.siteId()
                    : (!member.assignedSiteIds().isEmpty() ? member.assignedSiteIds().getFirst() : null);
            String resolvedSiteName = latest != null ? latest.siteName()
                    : (resolvedSiteId != null ? siteNames.getOrDefault(resolvedSiteId, "Assigned Site") : "Unassigned");

            rows.add(new LiveStaffRow(
                    member.membershipId(),
                    member.fullName(),
                    member.employeeCode(),
                    member.role(),
                    presenceStatus,
                    resolvedSiteId,
                    resolvedSiteName,
                    latest != null ? latest.effectiveAt() : null,
                    isLate,
                    lateMinutes,
                    latest != null ? latest.distanceMeters() : 0,
                    latest != null ? latest.geofenceStatus() : null
            ));
        }

        return new LiveBoardResponse(
                today,
                new LiveBoardSummary(members.size(), onSite, late, pendingReview, onLeave, off),
                rows
        );
    }

    public List<PayrollRow> buildPayrollSummary(AuthenticatedActor actor,
                                                LocalDate fromDate,
                                                LocalDate toDate,
                                                UUID siteId) {
        TenantContext.requireCurrent();
        List<OrganizationPublicApi.MemberSummary> members = orgApi.listVisibleMembers(actor.membershipId(), actor.role());
        List<AttendancePublicApi.AttendanceEventView> events = attendanceApi.listEventsForDateRange(
                actor.membershipId(), actor.role(), fromDate, toDate, siteId);

        Map<String, MutablePayrollAcc> accMap = new LinkedHashMap<>();
        Map<UUID, OrganizationPublicApi.MemberSummary> memberMap = new LinkedHashMap<>();
        members.forEach(m -> memberMap.put(m.membershipId(), m));

        for (var ev : events) {
            if ("REJECTED".equals(ev.geofenceStatus())) {
                continue;
            }
            var m = memberMap.get(ev.membershipId());
            if (m == null) {
                continue;
            }
            String key = ev.membershipId() + ":" + ev.siteId();
            var acc = accMap.computeIfAbsent(key, k -> new MutablePayrollAcc(
                    m.membershipId(), m.fullName(), m.employeeCode(), m.role(), ev.siteName()));

            if ("PENDING_REVIEW".equals(ev.geofenceStatus())) {
                acc.pendingReview++;
            }
            if ("CLOCK_IN".equals(ev.eventType()) && ev.isLate()) {
                acc.lateArrivals++;
                acc.totalLateMinutes += ev.lateMinutes();
            }
            if ("CLOCK_OUT".equals(ev.eventType())) {
                acc.shiftsCompleted++;
                acc.totalWorkedMinutes += ev.workedMinutes();
                acc.overtimeMinutes += ev.overtimeMinutes();
            }
        }

        return accMap.values().stream()
                .map(a -> new PayrollRow(
                        a.membershipId,
                        a.fullName,
                        a.employeeCode,
                        a.role,
                        a.siteName,
                        a.shiftsCompleted,
                        a.lateArrivals,
                        a.totalLateMinutes,
                        Math.max(0, a.totalWorkedMinutes - a.overtimeMinutes),
                        a.overtimeMinutes,
                        a.totalWorkedMinutes,
                        a.pendingReview
                ))
                .sorted(Comparator.comparing(PayrollRow::fullName))
                .toList();
    }

    public String exportPayrollCsv(AuthenticatedActor actor, LocalDate fromDate, LocalDate toDate, UUID siteId) {
        List<PayrollRow> rows = buildPayrollSummary(actor, fromDate, toDate, siteId);
        StringBuilder sb = new StringBuilder();
        sb.append("Employee Name,Employee Code,Role,Site,From Date,To Date,Shifts Completed,Late Arrivals,Late Minutes,Regular Hours,Overtime Hours,Total Hours,Flagged Pending Review\r\n");
        for (PayrollRow r : rows) {
            sb.append(csvCell(r.fullName())).append(',')
                    .append(csvCell(r.employeeCode() != null ? r.employeeCode() : "")).append(',')
                    .append(csvCell(r.role())).append(',')
                    .append(csvCell(r.siteName())).append(',')
                    .append(fromDate).append(',')
                    .append(toDate).append(',')
                    .append(r.shiftsCompleted()).append(',')
                    .append(r.lateArrivals()).append(',')
                    .append(r.totalLateMinutes()).append(',')
                    .append(formatHours(r.regularMinutes())).append(',')
                    .append(formatHours(r.overtimeMinutes())).append(',')
                    .append(formatHours(r.totalWorkedMinutes())).append(',')
                    .append(r.pendingReviewCount())
                    .append("\r\n");
        }
        return sb.toString();
    }

    private static String formatHours(int minutes) {
        return String.format(java.util.Locale.US, "%.2f", minutes / 60.0);
    }

    private static String csvCell(String raw) {
        String safe = raw == null ? "" : raw;
        if (!safe.isEmpty() && ("=+-@\t\r".indexOf(safe.charAt(0)) >= 0)) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static final class MutablePayrollAcc {
        final UUID membershipId;
        final String fullName;
        final String employeeCode;
        final String role;
        final String siteName;
        int shiftsCompleted;
        int lateArrivals;
        int totalLateMinutes;
        int totalWorkedMinutes;
        int overtimeMinutes;
        int pendingReview;

        MutablePayrollAcc(UUID membershipId, String fullName, String employeeCode, String role, String siteName) {
            this.membershipId = membershipId;
            this.fullName = fullName;
            this.employeeCode = employeeCode;
            this.role = role;
            this.siteName = siteName;
        }
    }
}
