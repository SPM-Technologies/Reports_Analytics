package com.hospital.patientreg.reporting.saved;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.patientreg.reporting.query.InvalidQueryException;
import com.hospital.patientreg.reporting.query.QueryBuilder;
import com.hospital.patientreg.reporting.query.QueryRequest;
import com.hospital.patientreg.reporting.query.QueryService;
import java.util.List;
import java.util.Set;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Saved reports. Every definition is validated with the same query builder used for Run, so a
 * stored report can always be opened and run. A report belongs to a hospital and is shared with that
 * hospital's report users; its author and the hospital's administrators may change or delete it.
 * Opening any report shows the viewer's own data only (the hospital scope is applied when it runs).
 */
@Service
public class SavedReportService {

    private static final int MAX_DEFINITION_CHARS = 50_000;

    private final SavedReportRepository repository;
    private final QueryService queryService;
    private final QueryBuilder builder;
    private final ObjectMapper json;
    private final AuditTrail audit;

    public SavedReportService(SavedReportRepository repository, QueryService queryService, QueryBuilder builder,
                              ObjectMapper reportingObjectMapper, AuditTrail audit) {
        this.audit = audit;
        this.repository = repository;
        this.queryService = queryService;
        this.builder = builder;
        this.json = reportingObjectMapper;
    }

    /** Report details sent by the UI when saving. */
    public record SaveRequest(String name, String description, JsonNode definition, String visibility) {
        public SaveRequest(String name, String description, JsonNode definition) {
            this(name, description, definition, null);
        }
    }

    /**
     * PRIVATE or HOSPITAL. Sharing with the hospital needs PUBLISH_SHARED there; without a choice a report is
     * shared when the user may share, private otherwise.
     */
    private static String visibility(String requested, int hospital, String current) {
        Tenant t = TenantContext.require();
        boolean mayShare = t.can(Permission.PUBLISH_SHARED, hospital);
        String v = requested == null ? (current != null ? current : mayShare ? "HOSPITAL" : "PRIVATE") : requested;
        if (!v.equals("PRIVATE") && !v.equals("HOSPITAL")) {
            throw new InvalidQueryException("Visibility must be PRIVATE or HOSPITAL");
        }
        if (v.equals("HOSPITAL") && !mayShare && !v.equals(current)) {
            throw Tenant.forbidden("You may not share reports with the hospital; keep it private or ask an administrator");
        }
        return v;
    }

    public List<SavedReport> list() {
        Set<Integer> scope = QueryService.analyticsScope();
        return repository.list(scope).stream().map(SavedReportService::withAccess).toList();
    }

    /** A template or a report of one of the user's hospitals; anything else is "not found". */
    public SavedReport get(long id) {
        Set<Integer> scope = QueryService.analyticsScope();
        return repository.find(id)
                .filter(r -> r.template() || (r.hospitalId() != null && scope.contains(r.hospitalId())))
                .map(SavedReportService::withAccess)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
    }

    private static SavedReport withAccess(SavedReport r) {
        Tenant t = TenantContext.require();
        String hospital = r.hospitalId() == null ? null
                : t.hospital(r.hospitalId()).map(h -> h.code() + " " + h.name()).orElse(null);
        boolean editable = !r.template() && r.hospitalId() != null
                && ((r.createdBy() != null && r.createdBy() == t.userId()) || t.can(Permission.MANAGE_USERS, r.hospitalId()));
        return r.withAccess(hospital, editable);
    }

    private static void requireEditable(SavedReport r) {
        if (!Boolean.TRUE.equals(r.canEdit())) {
            throw Tenant.forbidden("Only the author of this report or an administrator of its hospital can change it.");
        }
    }

    /** New reports belong to the active hospital, or to the first hospital whose reports the user may see. */
    private static int ownerHospital() {
        Tenant t = TenantContext.require();
        Set<Integer> scope = QueryService.analyticsScope();
        return scope.contains(t.activeHospitalId()) ? t.activeHospitalId() : scope.iterator().next();
    }

    public SavedReport create(SaveRequest request) {
        String definition = validated(request);
        Tenant t = TenantContext.require();
        int hospital = ownerHospital();
        long id = repository.insert(request.name().trim(), description(request), definition, hospital, t.userId(), t.displayName(),
                visibility(request.visibility(), hospital, null));
        audit.record("SAVED_REPORT_CHANGED", hospital, "created: " + request.name().trim());
        return get(id);
    }

    public SavedReport update(long id, SaveRequest request) {
        SavedReport existing = get(id);
        if (existing.template()) {
            throw new InvalidQueryException("Built-in templates cannot be changed; use Save as to keep your own copy.");
        }
        requireEditable(existing);
        String definition = validated(request);
        repository.update(id, request.name().trim(), description(request), definition,
                visibility(request.visibility(), existing.hospitalId(), existing.visibility()));
        audit.record("SAVED_REPORT_CHANGED", existing.hospitalId(), "updated: " + request.name().trim());
        return get(id);
    }

    public void delete(long id) {
        SavedReport existing = get(id);
        if (existing.template()) {
            throw new InvalidQueryException("Built-in templates cannot be deleted.");
        }
        requireEditable(existing);
        repository.delete(id);
        audit.record("SAVED_REPORT_CHANGED", existing.hospitalId(), "deleted: " + existing.name());
    }

    private String validated(SaveRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new InvalidQueryException("Enter a name for the report");
        }
        if (request.name().trim().length() > 120) {
            throw new InvalidQueryException("The name can have at most 120 characters");
        }
        if (request.description() != null && request.description().length() > 400) {
            throw new InvalidQueryException("The description can have at most 400 characters");
        }
        JsonNode def = request.definition();
        if (def == null || !def.isObject() || !def.has("query")) {
            throw new InvalidQueryException("The report definition is missing");
        }
        try {
            QueryRequest query = json.treeToValue(def.get("query"), QueryRequest.class);
            builder.build(query, queryService.dataset(query.dataset()), QueryService.analyticsScope());   // same validation as Run
            String text = json.writeValueAsString(def);
            if (text.length() > MAX_DEFINITION_CHARS) {
                throw new InvalidQueryException("The report definition is too large");
            }
            return text;
        } catch (JsonProcessingException e) {
            throw new InvalidQueryException("The report definition is not valid");
        }
    }

    private static String description(SaveRequest request) {
        return request.description() == null || request.description().isBlank() ? null : request.description().trim();
    }
}
