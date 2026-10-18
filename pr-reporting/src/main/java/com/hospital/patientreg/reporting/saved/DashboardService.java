package com.hospital.patientreg.reporting.saved;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.patientreg.reporting.query.InvalidQueryException;
import com.hospital.patientreg.reporting.query.QueryService;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dashboards (stage 5b): several saved reports as tiles on one page, with filters applied to every tile.
 * A dashboard holds only references and layout, never data: each tile is run as the viewer, with the
 * viewer's own hospitals and field rules. Private to its author, or shared with the hospital
 * (PUBLISH_SHARED); author and hospital administrators may change it.
 * Definition: {"tiles": [{"reportId": 12, "title": "...", "width": 1|2, "view": "table"|"chart"}], "filters": [...]}.
 */
@Service
public class DashboardService {

    private static final int MAX_TILES = 12;
    private static final int MAX_FILTERS = 10;
    private static final int MAX_DEFINITION_CHARS = 20_000;

    private final NamedParameterJdbcTemplate meta;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final SavedReportService reports;

    public DashboardService(@Qualifier("reportingMetaJdbc") NamedParameterJdbcTemplate meta, ObjectMapper reportingObjectMapper,
                            AuditTrail audit, SavedReportService reports) {
        this.reports = reports;
        this.meta = meta;
        this.json = reportingObjectMapper;
        this.audit = audit;
    }

    public record SaveRequest(String name, String description, JsonNode definition, String visibility) {
    }

    public List<Map<String, Object>> list() {
        Set<Integer> scope = QueryService.analyticsScope();
        return meta.query("""
                SELECT id, hospital_id, name, description, visibility, created_by, created_by_name, updated_at, NULL AS definition
                FROM reporting.dashboard WHERE hospital_id IN (:h) ORDER BY lower(name)""", Map.of("h", scope), this::row);
    }

    public Map<String, Object> get(long id) {
        Set<Integer> scope = QueryService.analyticsScope();
        return meta.query("""
                SELECT id, hospital_id, name, description, visibility, created_by, created_by_name, updated_at, definition
                FROM reporting.dashboard WHERE id = :id AND hospital_id IN (:h)""", Map.of("id", id, "h", scope), this::row)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard not found"));
    }

    public Map<String, Object> create(SaveRequest r) {
        Tenant t = TenantContext.require();
        Set<Integer> scope = QueryService.analyticsScope();
        int hospital = scope.contains(t.activeHospitalId()) ? t.activeHospitalId() : scope.iterator().next();
        String definition = validated(r);
        Long id = meta.queryForObject("""
                INSERT INTO reporting.dashboard (hospital_id, name, description, definition, visibility, created_by, created_by_name)
                VALUES (:h, :n, :d, :def, :v, :u, :un) RETURNING id""",
                new MapSqlParameterSource("h", hospital).addValue("n", r.name().trim()).addValue("d", description(r))
                        .addValue("def", definition).addValue("v", visibility(r.visibility(), hospital, null))
                        .addValue("u", t.userId()).addValue("un", t.displayName()), Long.class);
        audit.record("SAVED_REPORT_CHANGED", hospital, "dashboard created: " + r.name().trim());
        return get(id == null ? 0 : id);
    }

    public Map<String, Object> update(long id, SaveRequest r) {
        Map<String, Object> existing = get(id);
        requireEditable(existing);
        int hospital = (Integer) existing.get("hospitalId");
        String definition = validated(r);
        meta.update("""
                UPDATE reporting.dashboard SET name = :n, description = :d, definition = :def, visibility = :v, updated_at = now()
                WHERE id = :id""",
                new MapSqlParameterSource("id", id).addValue("n", r.name().trim()).addValue("d", description(r)).addValue("def", definition)
                        .addValue("v", visibility(r.visibility(), hospital, (String) existing.get("visibility"))));
        audit.record("SAVED_REPORT_CHANGED", hospital, "dashboard updated: " + r.name().trim());
        return get(id);
    }

    public void delete(long id) {
        Map<String, Object> existing = get(id);
        requireEditable(existing);
        meta.update("DELETE FROM reporting.dashboard WHERE id = :id", Map.of("id", id));
        audit.record("SAVED_REPORT_CHANGED", (Integer) existing.get("hospitalId"), "dashboard deleted: " + existing.get("name"));
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> row(ResultSet rs, int i) throws SQLException {
        Tenant t = TenantContext.require();
        Map<String, Object> out = new LinkedHashMap<>();
        int hospital = rs.getInt("hospital_id");
        long by = rs.getLong("created_by");
        Long createdBy = rs.wasNull() ? null : by;
        out.put("id", rs.getLong("id"));
        out.put("hospitalId", hospital);
        out.put("hospital", t.hospital(hospital).map(h -> h.code() + " " + h.name()).orElse(null));
        out.put("name", rs.getString("name"));
        out.put("description", rs.getString("description"));
        out.put("visibility", rs.getString("visibility"));
        out.put("owner", rs.getString("created_by_name"));
        out.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
        out.put("canEdit", (createdBy != null && createdBy == t.userId()) || t.can(Permission.MANAGE_USERS, hospital));
        String def = rs.getString("definition");
        if (def != null) {
            try {
                out.put("definition", json.readTree(def));
            } catch (JsonProcessingException e) {
                throw new SQLException("Dashboard " + rs.getLong("id") + " has an unreadable definition", e);
            }
        }
        return out;
    }

    private static void requireEditable(Map<String, Object> d) {
        if (!Boolean.TRUE.equals(d.get("canEdit"))) {
            throw Tenant.forbidden("Only the author of this dashboard or an administrator of its hospital can change it.");
        }
    }

    private static String visibility(String requested, int hospital, String current) {
        Tenant t = TenantContext.require();
        String v = requested == null ? (current == null ? "PRIVATE" : current) : requested;
        if (!v.equals("PRIVATE") && !v.equals("HOSPITAL")) {
            throw new InvalidQueryException("Visibility must be PRIVATE or HOSPITAL");
        }
        if (v.equals("HOSPITAL") && !v.equals(current) && !t.can(Permission.PUBLISH_SHARED, hospital)) {
            throw Tenant.forbidden("You may not share dashboards with the hospital");
        }
        return v;
    }

    private String validated(SaveRequest r) {
        if (r == null || r.name() == null || r.name().isBlank() || r.name().trim().length() > 120) {
            throw new InvalidQueryException("Enter a dashboard name of 1-120 characters");
        }
        JsonNode def = r.definition();
        if (def == null || !def.isObject() || !def.path("tiles").isArray()) {
            throw new InvalidQueryException("The dashboard definition is missing its tiles");
        }
        if (def.get("tiles").size() > MAX_TILES) {
            throw new InvalidQueryException("At most " + MAX_TILES + " tiles per dashboard");
        }
        for (JsonNode tile : def.get("tiles")) {
            if (!tile.path("reportId").canConvertToLong()) {
                throw new InvalidQueryException("Each tile needs a saved report");
            }
            try {
                reports.get(tile.path("reportId").asLong());   // a template or a report this user may open
            } catch (ResponseStatusException e) {
                throw new InvalidQueryException("A tile's report no longer exists or is not available to you");
            }
            int width = tile.path("width").asInt(1);
            if (width < 1 || width > 2) {
                throw new InvalidQueryException("Tile width is 1 or 2");
            }
            if (tile.path("title").asText("").length() > 120) {
                throw new InvalidQueryException("Tile titles: at most 120 characters");
            }
        }
        if (def.has("filters") && (!def.get("filters").isArray() || def.get("filters").size() > MAX_FILTERS)) {
            throw new InvalidQueryException("At most " + MAX_FILTERS + " dashboard filters");
        }
        try {
            String text = json.writeValueAsString(def);
            if (text.length() > MAX_DEFINITION_CHARS) {
                throw new InvalidQueryException("The dashboard definition is too large");
            }
            return text;
        } catch (JsonProcessingException e) {
            throw new InvalidQueryException("The dashboard definition is not valid");
        }
    }

    private static String description(SaveRequest r) {
        if (r.description() == null || r.description().isBlank()) {
            return null;
        }
        if (r.description().length() > 400) {
            throw new InvalidQueryException("The description can have at most 400 characters");
        }
        return r.description().trim();
    }
}
