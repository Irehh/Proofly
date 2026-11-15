package com.proofly;

import com.proofly.attendance.AttendancePublicApi;
import com.proofly.attendance.AttendanceService;
import com.proofly.common.AuthenticatedActor;
import com.proofly.common.ProoflyException;
import com.proofly.common.TenantId;
import com.proofly.identity.AuthDtos;
import com.proofly.identity.AuthService;
import com.proofly.leave.LeaveService;
import com.proofly.organization.OrganizationService;
import com.proofly.reporting.ReportingService;
import com.proofly.site.SiteService;
import com.proofly.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class RoleOperationsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("proofly_control")
            .withUsername("proofly_admin")
            .withPassword("proofly_local_dev_password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("proofly.control-plane.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("proofly.control-plane.username", POSTGRES::getUsername);
        registry.add("proofly.control-plane.password", POSTGRES::getPassword);
        registry.add("proofly.tenancy.default-db-host", POSTGRES::getHost);
        registry.add("proofly.tenancy.default-db-port", POSTGRES::getFirstMappedPort);
        registry.add("proofly.tenancy.db-username", POSTGRES::getUsername);
        registry.add("proofly.tenancy.db-password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired AuthService authService;
    @Autowired OrganizationService orgService;
    @Autowired SiteService siteService;
    @Autowired AttendanceService attendanceService;
    @Autowired LeaveService leaveService;
    @Autowired ReportingService reportingService;

    @Test
    void enforcesGeofencePostGisTeamScopeAndPayrollExport() {
        var session = authService.signupOrganization(
                new AuthDtos.SignupOrgRequest(
                        "Marina Ops", "marina-ops-" + System.nanoTime(), "Africa/Lagos",
                        "Ada Owner", "ada-" + System.nanoTime() + "@marina.ng", null,
                        "StrongPass#2026!", "fp-owner", "Test", AuthDtos.ClientType.WEB
                ),
                "127.0.0.1"
        );
        UUID tid = session.response().user().tenantId();
        AuthenticatedActor owner = new AuthenticatedActor(
                session.response().user().userId(), tid, session.response().user().membershipId(), "SUPER_ADMIN"
        );

        TenantContext.runWithTenant(new TenantId(tid), () -> {
            var site = siteService.createSite(owner, new SiteService.CreateSiteRequest(
                    "Apapa Gate", "APAPA-1", "Wharf Rd", 6.4474, 3.3641,
                    150, "Africa/Lagos", "08:00", 15, 480
            ), "127.0.0.1");

            var managerMember = orgService.inviteStaff(owner, new OrganizationService.InviteStaffRequest(
                    "PHONE_PIN", "Tunde Manager", "tunde-" + System.nanoTime() + "@marina.ng",
                    "+23480111" + String.format("%05d", Math.abs((int) (System.nanoTime() % 90000))),
                    "1234", "MANAGER", owner.membershipId(), "Depot Lead", "EMP-02"
            ), "127.0.0.1");

            var staffMember = orgService.inviteStaff(owner, new OrganizationService.InviteStaffRequest(
                    "PHONE_PIN", "Chidi Loader", null,
                    "+23480222" + String.format("%05d", Math.abs((int) (System.nanoTime() % 90000))),
                    "5678", "EMPLOYEE", managerMember.membershipId(), "Loader", "EMP-03"
            ), "127.0.0.1");

            var otherStaff = orgService.inviteStaff(owner, new OrganizationService.InviteStaffRequest(
                    "PHONE_PIN", "Bola Unassigned", null,
                    "+23480333" + String.format("%05d", Math.abs((int) (System.nanoTime() % 90000))),
                    "4321", "EMPLOYEE", owner.membershipId(), "Guard", "EMP-04"
            ), "127.0.0.1");

            siteService.setMemberAssignments(owner, staffMember.membershipId(), List.of(
                    new SiteService.SiteAssignmentItem(site.id(), true, "09:00", 10, 480)
            ), "127.0.0.1");

            AuthenticatedActor staffActor = new AuthenticatedActor(staffMember.userId(), tid, staffMember.membershipId(), "EMPLOYEE");
            AuthenticatedActor mgrActor = new AuthenticatedActor(managerMember.userId(), tid, managerMember.membershipId(), "MANAGER");

            // Employee visibility check: sees only self
            assertThat(orgService.listVisibleMembers(staffActor.membershipId(), "EMPLOYEE"))
                    .extracting(m -> m.membershipId())
                    .containsExactly(staffMember.membershipId());

            // Manager visibility check: sees self + Chidi, NOT Bola
            assertThat(orgService.listVisibleMembers(mgrActor.membershipId(), "MANAGER"))
                    .extracting(m -> m.membershipId())
                    .containsExactlyInAnyOrder(managerMember.membershipId(), staffMember.membershipId());

            // Outside geofence (~500m away) -> recorded as PENDING_REVIEW, never dropped
            AttendancePublicApi.AttendanceEventView flagged = attendanceService.clockIn(
                    staffActor,
                    new AttendanceService.ClockRequest(site.id(), 6.4520, 3.3641, 15, "Traffic gate drop"),
                    "127.0.0.1"
            );
            assertThat(flagged.geofenceStatus()).isEqualTo("PENDING_REVIEW");
            assertThat(flagged.distanceMeters()).isGreaterThan(150);

            // Manager approves direct report's flagged event
            var approved = attendanceService.reviewFlaggedEvent(
                    mgrActor,
                    flagged.id(),
                    new AttendanceService.ReviewDecisionRequest("APPROVED", "Confirmed port gate inspection"),
                    "127.0.0.1"
            );
            assertThat(approved.geofenceStatus()).isEqualTo("APPROVED");

            // Employee clocks out inside fence
            var clockedOut = attendanceService.clockOut(
                    staffActor,
                    new AttendanceService.ClockRequest(site.id(), 6.4474, 3.3641, 10, "Done"),
                    "127.0.0.1"
            );
            assertThat(clockedOut.geofenceStatus()).isEqualTo("VERIFIED");

            // Correction request + Manager approval
            var corr = attendanceService.requestCorrection(
                    staffActor,
                    new AttendanceService.CreateCorrectionRequest(flagged.id(), Instant.now().minus(9, ChronoUnit.HOURS), "Forgot badge tap earlier"),
                    "127.0.0.1"
            );
            var decidedCorr = attendanceService.reviewCorrection(
                    mgrActor,
                    corr.id(),
                    new AttendanceService.ReviewDecisionRequest("APPROVED", "Verified with gate logbook"),
                    "127.0.0.1"
            );
            assertThat(decidedCorr.status()).isEqualTo("APPROVED");

            // Leave routed to manager + manager isolation check
            var leave = leaveService.submitLeave(
                    staffActor,
                    new LeaveService.CreateLeaveRequest("ANNUAL", LocalDate.now().plusDays(1), LocalDate.now().plusDays(2), "Family trip"),
                    "127.0.0.1"
            );
            assertThat(leave.managerMembershipId()).isEqualTo(managerMember.membershipId());

            AuthenticatedActor otherActor = new AuthenticatedActor(otherStaff.userId(), tid, otherStaff.membershipId(), "EMPLOYEE");
            var otherLeave = leaveService.submitLeave(
                    otherActor,
                    new LeaveService.CreateLeaveRequest("SICK", LocalDate.now().plusDays(1), LocalDate.now().plusDays(1), "Clinic visit"),
                    "127.0.0.1"
            );
            assertThatThrownBy(() -> leaveService.reviewLeave(
                    mgrActor, otherLeave.id(), new LeaveService.LeaveDecisionRequest("APPROVED", "Cross-team"), "127.0.0.1"
            )).isInstanceOf(ProoflyException.class);

            // Live Board & Payroll CSV
            var board = reportingService.getLiveBoard(mgrActor, null);
            assertThat(board.summary().totalVisibleStaff()).isEqualTo(2);

            String csv = reportingService.exportPayrollCsv(mgrActor, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), null);
            assertThat(csv).contains("Chidi Loader").contains("Apapa Gate");
        });
    }
}
