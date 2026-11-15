package com.proofly.site;

import com.proofly.audit.AuditWriter;
import com.proofly.common.AuthenticatedActor;
import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.organization.OrganizationPublicApi;
import com.proofly.tenancy.TenantContext;
import com.proofly.tenancy.TenantDirectoryService;
import com.proofly.tenancy.TenantSummary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Time;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SiteService implements SitePublicApi {

    public record CreateSiteRequest(
            String name,
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

    public record UpdateSiteRequest(
            String name,
            String address,
            Double latitude,
            Double longitude,
            Integer radiusMeters,
            String timezone,
            String shiftStartLocal,
            Integer lateGraceMinutes,
            Integer overtimeThresholdMinutes,
            Boolean isActive
    ) {}

    public record SiteAssignmentItem(
            UUID siteId,
            boolean isPrimary,
            String shiftStartOverride,
            Integer lateGraceOverride,
            Integer overtimeThresholdOverride
    ) {}

    private final JdbcClient tenantJdbc;
    private final TenantDirectoryService directoryService;
    private final OrganizationPublicApi orgApi;
    private final AuditWriter auditWriter;

    public SiteService(DataSource tenantRoutingDataSource,
                       TenantDirectoryService directoryService,
                       OrganizationPublicApi orgApi,
                       AuditWriter auditWriter) {
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
        this.directoryService = directoryService;
        this.orgApi = orgApi;
        this.auditWriter = auditWriter;
    }

    @Override
    public List<SiteView> listVisibleSites(UUID actorMembershipId, String actorRole) {
        TenantContext.requireCurrent();
        if ("SUPER_ADMIN".equals(actorRole) || "ADMIN".equals(actorRole)) {
            return tenantJdbc.sql("""
                    SELECT s.id, s.name, s.code, s.address, s.latitude, s.longitude, s.radius_meters,
                           s.timezone, to_char(s.shift_start_local, 'HH24:MI') AS shift_start,
                           s.late_grace_minutes, s.overtime_threshold_minutes, s.is_active,
                           (SELECT COUNT(1) FROM site_assignments sa WHERE sa.site_id = s.id) AS assigned_count
                    FROM sites s
                    ORDER BY s.is_active DESC, s.name ASC
                    """)
                    .query(SiteView.class)
                    .list();
        }
        return tenantJdbc.sql("""
                SELECT DISTINCT s.id, s.name, s.code, s.address, s.latitude, s.longitude, s.radius_meters,
                       s.timezone, to_char(COALESCE(sa.shift_start_override, s.shift_start_local), 'HH24:MI') AS shift_start,
                       COALESCE(sa.late_grace_override, s.late_grace_minutes) AS late_grace_minutes,
                       COALESCE(sa.overtime_threshold_override, s.overtime_threshold_minutes) AS overtime_threshold_minutes,
                       s.is_active,
                       (SELECT COUNT(1) FROM site_assignments sa2 WHERE sa2.site_id = s.id) AS assigned_count
                FROM sites s
                JOIN site_assignments sa ON sa.site_id = s.id
                JOIN memberships m ON m.id = sa.membership_id
                WHERE s.is_active = TRUE
                  AND (sa.membership_id = :mid OR (:isManager = TRUE AND m.manager_membership_id = :mid))
                ORDER BY s.name ASC
                """)
                .param("mid", actorMembershipId)
                .param("isManager", "MANAGER".equals(actorRole))
                .query(SiteView.class)
                .list();
    }

    @Transactional
    public SiteView createSite(AuthenticatedActor actor, CreateSiteRequest req, String ipAddress) {
        UUID tenantId = TenantContext.requireCurrent().value();
        TenantSummary.PlanQuota quota = directoryService.findTenantQuota(tenantId);

        Integer activeSites = tenantJdbc.sql("SELECT COUNT(1) FROM sites WHERE is_active = TRUE")
                .query(Integer.class)
                .single();
        if (activeSites != null && activeSites >= quota.maxSites()) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.VALIDATION_ERROR,
                    "Site quota reached for " + quota.planName() + " plan (" + quota.maxSites() + " active sites). Upgrade billing to add another site.");
        }

        validateCoordinates(req.latitude(), req.longitude());
        var defaults = orgApi.getOrganizationSettings();

        String normalizedCode = normalizeSiteCode(req.code() != null && !req.code().isBlank() ? req.code() : req.name());
        Integer existingCode = tenantJdbc.sql("SELECT COUNT(1) FROM sites WHERE code = :code")
                .param("code", normalizedCode)
                .query(Integer.class)
                .single();
        if (existingCode != null && existingCode > 0) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT,
                    "Site code '" + normalizedCode + "' already exists");
        }

        UUID siteId = UUID.randomUUID();
        int radius = clamp(req.radiusMeters() != null ? req.radiusMeters() : 150, 25, 20000);
        String tz = validateTimezone(req.timezone(), defaults.defaultTimezone());
        LocalTime shiftStart = parseTime(req.shiftStartLocal(), LocalTime.of(9, 0));
        int lateGrace = clamp(req.lateGraceMinutes() != null ? req.lateGraceMinutes() : defaults.lateGraceMinutes(), 0, 240);
        int overtime = clamp(req.overtimeThresholdMinutes() != null ? req.overtimeThresholdMinutes() : defaults.overtimeThresholdMinutes(), 60, 1440);

        tenantJdbc.sql("""
                INSERT INTO sites
                (id, name, code, address, latitude, longitude, center_point, radius_meters, timezone,
                 shift_start_local, late_grace_minutes, overtime_threshold_minutes, is_active)
                VALUES (:id, :name, :code, :addr, :lat, :lng,
                        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                        :radius, :tz, :shiftStart, :lateGrace, :overtime, TRUE)
                """)
                .param("id", siteId)
                .param("name", req.name().trim())
                .param("code", normalizedCode)
                .param("addr", req.address() != null ? req.address().trim() : null)
                .param("lat", req.latitude())
                .param("lng", req.longitude())
                .param("radius", radius)
                .param("tz", tz)
                .param("shiftStart", Time.valueOf(shiftStart))
                .param("lateGrace", lateGrace)
                .param("overtime", overtime)
                .update();

        // Automatically assign creator if no staff exist yet so first site is immediately testable under My Shift
        tenantJdbc.sql("""
                INSERT INTO site_assignments (id, membership_id, site_id, is_primary, assigned_by_membership_id)
                VALUES (:id, :mid, :sid, TRUE, :mid)
                ON CONFLICT (membership_id, site_id) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("mid", actor.membershipId())
                .param("sid", siteId)
                .update();

        auditWriter.writeTenantEvent(actor.membershipId(), "SITE_CREATED", "site", siteId.toString(),
                null, Map.of("name", req.name().trim(), "code", normalizedCode, "radiusMeters", radius),
                ipAddress, null);

        return findSiteById(siteId).orElseThrow();
    }

    @Transactional
    public SiteView updateSite(AuthenticatedActor actor, UUID siteId, UpdateSiteRequest req, String ipAddress) {
        SiteView before = findSiteById(siteId)
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Site not found"));

        double nextLat = req.latitude() != null ? req.latitude() : before.latitude();
        double nextLng = req.longitude() != null ? req.longitude() : before.longitude();
        validateCoordinates(nextLat, nextLng);

        int nextRadius = clamp(req.radiusMeters() != null ? req.radiusMeters() : before.radiusMeters(), 25, 20000);
        String nextTz = validateTimezone(req.timezone(), before.timezone());
        LocalTime nextShift = parseTime(req.shiftStartLocal(), LocalTime.parse(before.shiftStartLocal()));
        int nextLateGrace = clamp(req.lateGraceMinutes() != null ? req.lateGraceMinutes() : before.lateGraceMinutes(), 0, 240);
        int nextOvertime = clamp(req.overtimeThresholdMinutes() != null ? req.overtimeThresholdMinutes() : before.overtimeThresholdMinutes(), 60, 1440);
        boolean nextActive = req.isActive() != null ? req.isActive() : before.isActive();

        tenantJdbc.sql("""
                UPDATE sites
                SET name = :name,
                    address = :addr,
                    latitude = :lat,
                    longitude = :lng,
                    center_point = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                    radius_meters = :radius,
                    timezone = :tz,
                    shift_start_local = :shiftStart,
                    late_grace_minutes = :lateGrace,
                    overtime_threshold_minutes = :overtime,
                    is_active = :active,
                    version = version + 1,
                    updated_at = NOW()
                WHERE id = :id
                """)
                .param("name", req.name() != null && !req.name().isBlank() ? req.name().trim() : before.name())
                .param("addr", req.address() != null ? req.address().trim() : before.address())
                .param("lat", nextLat)
                .param("lng", nextLng)
                .param("radius", nextRadius)
                .param("tz", nextTz)
                .param("shiftStart", Time.valueOf(nextShift))
                .param("lateGrace", nextLateGrace)
                .param("overtime", nextOvertime)
                .param("active", nextActive)
                .param("id", siteId)
                .update();

        SiteView after = findSiteById(siteId).orElseThrow();
        auditWriter.writeTenantEvent(actor.membershipId(), "SITE_UPDATED", "site", siteId.toString(),
                Map.of("radiusMeters", before.radiusMeters(), "isActive", before.isActive()),
                Map.of("radiusMeters", after.radiusMeters(), "isActive", after.isActive()),
                ipAddress, null);
        return after;
    }

    @Transactional
    public List<SiteAssignmentView> setMemberAssignments(AuthenticatedActor actor,
                                                         UUID targetMembershipId,
                                                         List<SiteAssignmentItem> items,
                                                         String ipAddress) {
        orgApi.findMemberByMembershipId(targetMembershipId)
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Staff member not found"));

        tenantJdbc.sql("DELETE FROM site_assignments WHERE membership_id = :mid")
                .param("mid", targetMembershipId)
                .update();

        if (items != null) {
            boolean primaryAssigned = false;
            for (int i = 0; i < items.size(); i++) {
                SiteAssignmentItem item = items.get(i);
                findSiteById(item.siteId())
                        .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Site not found"));

                boolean isPrimary = item.isPrimary() && !primaryAssigned;
                if (isPrimary) {
                    primaryAssigned = true;
                }
                Time shiftOverride = item.shiftStartOverride() != null && !item.shiftStartOverride().isBlank()
                        ? Time.valueOf(parseTime(item.shiftStartOverride(), LocalTime.of(9, 0)))
                        : null;

                tenantJdbc.sql("""
                        INSERT INTO site_assignments
                        (id, membership_id, site_id, is_primary, shift_start_override,
                         late_grace_override, overtime_threshold_override, assigned_by_membership_id)
                        VALUES (:id, :mid, :sid, :primary, :shiftOvr, :graceOvr, :otOvr, :actorMid)
                        ON CONFLICT (membership_id, site_id) DO NOTHING
                        """)
                        .param("id", UUID.randomUUID())
                        .param("mid", targetMembershipId)
                        .param("sid", item.siteId())
                        .param("primary", isPrimary || (i == items.size() - 1 && !primaryAssigned))
                        .param("shiftOvr", shiftOverride)
                        .param("graceOvr", item.lateGraceOverride())
                        .param("otOvr", item.overtimeThresholdOverride())
                        .param("actorMid", actor.membershipId())
                        .update();
            }
        }

        auditWriter.writeTenantEvent(actor.membershipId(), "SITE_ASSIGNMENTS_UPDATED", "membership",
                targetMembershipId.toString(), null,
                Map.of("siteCount", items != null ? items.size() : 0), ipAddress, null);

        return listAssignmentsForMember(targetMembershipId);
    }

    public List<SiteAssignmentView> listAssignmentsForMember(UUID membershipId) {
        TenantContext.requireCurrent();
        return tenantJdbc.sql("""
                SELECT sa.id, sa.membership_id, sa.site_id, s.name AS site_name, s.code AS site_code,
                       s.timezone, sa.is_primary,
                       to_char(COALESCE(sa.shift_start_override, s.shift_start_local), 'HH24:MI') AS shift_start,
                       COALESCE(sa.late_grace_override, s.late_grace_minutes) AS late_grace_minutes,
                       COALESCE(sa.overtime_threshold_override, s.overtime_threshold_minutes) AS overtime_threshold_minutes
                FROM site_assignments sa
                JOIN sites s ON s.id = sa.site_id
                WHERE sa.membership_id = :mid AND s.is_active = TRUE
                ORDER BY sa.is_primary DESC, s.name ASC
                """)
                .param("mid", membershipId)
                .query(SiteAssignmentView.class)
                .list();
    }

    @Override
    public Optional<EffectiveSiteRule> resolveEffectiveRule(UUID membershipId, UUID siteId) {
        TenantContext.requireCurrent();
        return tenantJdbc.sql("""
                SELECT s.id AS site_id, s.name AS site_name, s.radius_meters, s.timezone,
                       COALESCE(sa.shift_start_override, s.shift_start_local) AS effective_shift,
                       COALESCE(sa.late_grace_override, s.late_grace_minutes) AS effective_grace,
                       COALESCE(sa.overtime_threshold_override, s.overtime_threshold_minutes) AS effective_ot,
                       (sa.id IS NOT NULL) AS is_assigned
                FROM sites s
                LEFT JOIN site_assignments sa ON sa.site_id = s.id AND sa.membership_id = :mid
                WHERE s.id = :sid AND s.is_active = TRUE
                """)
                .param("mid", membershipId)
                .param("sid", siteId)
                .query((rs, rowNum) -> new EffectiveSiteRule(
                        rs.getObject("site_id", UUID.class),
                        rs.getString("site_name"),
                        rs.getInt("radius_meters"),
                        rs.getString("timezone"),
                        rs.getTime("effective_shift").toLocalTime(),
                        rs.getInt("effective_grace"),
                        rs.getInt("effective_ot"),
                        rs.getBoolean("is_assigned")
                ))
                .optional();
    }

    @Override
    public int computePostGisDistanceMeters(UUID siteId, double latitude, double longitude) {
        validateCoordinates(latitude, longitude);
        Double meters = tenantJdbc.sql("""
                SELECT ST_Distance(
                    center_point,
                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
                )
                FROM sites
                WHERE id = :sid
                """)
                .param("lng", longitude)
                .param("lat", latitude)
                .param("sid", siteId)
                .query(Double.class)
                .optional()
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Site not found"));
        return (int) Math.round(meters);
    }

    private Optional<SiteView> findSiteById(UUID siteId) {
        return tenantJdbc.sql("""
                SELECT s.id, s.name, s.code, s.address, s.latitude, s.longitude, s.radius_meters,
                       s.timezone, to_char(s.shift_start_local, 'HH24:MI') AS shift_start,
                       s.late_grace_minutes, s.overtime_threshold_minutes, s.is_active,
                       (SELECT COUNT(1) FROM site_assignments sa WHERE sa.site_id = s.id) AS assigned_count
                FROM sites s
                WHERE s.id = :sid
                """)
                .param("sid", siteId)
                .query(SiteView.class)
                .optional();
    }

    private static void validateCoordinates(double lat, double lng) {
        if (Double.isNaN(lat) || Double.isNaN(lng) || lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Coordinates must be valid WGS84 latitude (-90..90) and longitude (-180..180)");
        }
    }

    private static String normalizeSiteCode(String raw) {
        String cleaned = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (cleaned.length() < 2) {
            return "SITE-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        }
        return cleaned.substring(0, Math.min(64, cleaned.length()));
    }

    private static String validateTimezone(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(raw.trim()).getId();
        } catch (Exception e) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Invalid timezone: " + raw);
        }
    }

    private static LocalTime parseTime(String raw, LocalTime fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(raw.trim());
        } catch (Exception e) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Shift start time must be HH:mm (e.g. 08:30)");
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
