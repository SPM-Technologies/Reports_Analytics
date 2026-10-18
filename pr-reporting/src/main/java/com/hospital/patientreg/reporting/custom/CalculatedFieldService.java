package com.hospital.patientreg.reporting.custom;

import com.hospital.patientreg.reporting.formula.CalculatedFields;
import com.hospital.patientreg.reporting.query.InvalidQueryException;
import com.hospital.patientreg.reporting.query.QueryRequest.CalculatedSpec;
import com.hospital.patientreg.reporting.query.QueryService;
import com.hospital.patientreg.reporting.semantic.Dataset;
import com.hospital.patientreg.reporting.semantic.Field;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Calculated fields shared with a hospital (architecture 10.2): anyone may use them in reports; only users
 * with PUBLISH_SHARED in the field's hospital may add or change them. A formula is checked against the
 * publisher's own fields; when a change would alter what reports using the field get (its type, or column
 * versus total), it is refused while reports use it, listing those reports.
 */
@Service
public class CalculatedFieldService {

    /** shared calculated fields per hospital and dataset */
    static final int MAX_SHARED_PER_HOSPITAL = 100;

    private final CustomisationRepository repo;
    private final QueryService queryService;
    private final AuditTrail audit;
    private final Clock clock;

    public CalculatedFieldService(CustomisationRepository repo, QueryService queryService, AuditTrail audit, Clock reportingClock) {
        this.repo = repo;
        this.queryService = queryService;
        this.audit = audit;
        this.clock = reportingClock;
    }

    /** The shared fields of the user's hospitals, with what the user may do with them. */
    public List<Map<String, Object>> list(String datasetId) {
        Set<Integer> scope = QueryService.analyticsScope();
        Tenant t = TenantContext.require();
        return repo.calculatedFields(datasetId, scope, false).stream().map(c -> describe(c, t)).toList();
    }

    public Map<String, Object> create(String datasetId, String label, String formula, String description) {
        Tenant t = TenantContext.require();
        int hospital = t.activeHospitalId();
        if (!t.can(Permission.PUBLISH_SHARED, hospital) || !t.can(Permission.ANALYTICS, hospital)) {
            throw Tenant.forbidden("You may not share calculated fields in " + t.active().name());
        }
        String l = label(label);
        if (repo.calculatedLabelExists(hospital, datasetId, l, null)) {
            throw new InvalidQueryException("A shared field called \"" + l + "\" already exists");
        }
        // every report of the hospital compiles all its shared fields: keep the list bounded (review finding)
        if (repo.calculatedFields(datasetId, List.of(hospital), false).size() >= MAX_SHARED_PER_HOSPITAL) {
            throw new InvalidQueryException("A hospital can share at most " + MAX_SHARED_PER_HOSPITAL
                    + " calculated fields; switch off or reuse one");
        }
        Field compiled = check(datasetId, l, formula, null);
        long id = repo.insertCalculated(hospital, datasetId, l, CalculatedFields.canonical(formula.trim(), queryService.dataset(datasetId)), description(description), compiled.type().json(),
                compiled.isMeasure(), t.userId(), t.displayName());
        audit.record("SETTINGS_CHANGED", hospital, "shared calculated field added: " + l + " = " + formula.trim());
        return describe(repo.calculatedField(id).orElseThrow(), t);
    }

    public Map<String, Object> update(long id, String label, String formula, String description, boolean active) {
        Tenant t = TenantContext.require();
        SharedCalculatedField c = editable(id, t);
        String l = label(label);
        if (repo.calculatedLabelExists(c.hospitalId(), c.datasetId(), l, id)) {
            throw new InvalidQueryException("A shared field called \"" + l + "\" already exists");
        }
        Field compiled = check(c.datasetId(), l, formula, c.key());
        boolean changesMeaning = !compiled.type().json().equals(c.resultType()) || compiled.isMeasure() != c.aggregate();
        // the names the editor may see, plus a count of colleagues' private reports (row-level security hides them)
        List<String> users = repo.reportsUsing(c.key());
        long all = repo.reportCountUsing(id);
        if ((changesMeaning || !active) && all > 0) {
            List<String> named = new java.util.ArrayList<>(users);
            if (all > users.size()) {
                named.add((all - users.size()) + " private report(s) of colleagues");
            }
            throw new InvalidQueryException((active ? "This change alters the field's type, " : "The field cannot be switched off, ")
                    + "it is used by: " + String.join(", ", named) + ". Add a new shared field instead.");
        }
        repo.updateCalculated(id, l, CalculatedFields.canonical(formula.trim(), queryService.dataset(c.datasetId())), description(description), compiled.type().json(), compiled.isMeasure(), active);
        audit.record("SETTINGS_CHANGED", c.hospitalId(), "shared calculated field changed: " + l + " = " + formula.trim()
                + (active ? "" : " (switched off)"));
        return describe(repo.calculatedField(id).orElseThrow(), t);
    }

    public List<String> usage(long id) {
        SharedCalculatedField c = visible(id);
        return repo.reportsUsing(c.key());
    }

    /**
     * Checks a formula as a report would use it: against the user's fields, after the report's own earlier
     * calculated fields. Returns its kind and type, or throws with the position of the mistake.
     */
    public Map<String, Object> validate(String datasetId, String formula, List<CalculatedSpec> earlier) {
        Dataset ds = queryService.dataset(datasetId);
        int i = 0;
        if (earlier != null) {
            for (CalculatedSpec c : earlier) {
                if (c != null && c.name() != null && c.formula() != null) {
                    try {
                        ds = CalculatedFields.plus(ds, CalculatedFields.compile(c.name(), c.label() == null ? c.name() : c.label(),
                                c.formula(), "report", null, ds, "v" + (i++), () -> LocalDate.now(clock)));
                    } catch (InvalidQueryException ignored) {
                        // an earlier field with a mistake is simply not available
                    }
                }
            }
        }
        Field f = CalculatedFields.compile("calc_check", "Check", formula, "report", null, ds, "check", () -> LocalDate.now(clock));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", f.isMeasure() ? "measure" : "column");
        out.put("type", f.type().json());
        // the form to store: [field_name] instead of [Label], so it works for every viewer and after renames
        out.put("formula", CalculatedFields.canonical(formula, ds));
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private Field check(String datasetId, String label, String formula, String exceptKey) {
        if (formula == null || formula.isBlank()) {
            throw new InvalidQueryException("Enter a formula");
        }
        Dataset ds = queryService.dataset(datasetId);
        if (exceptKey != null) {
            Dataset full = ds;
            ds = new Dataset(full.id(), full.label(), full.description(), full.table(), full.version(), full.freshnessColumn(),
                    full.defaultSort(), full.fields().stream().filter(f -> !f.name().equals(exceptKey)).toList());
        }
        if (ds.fields().stream().anyMatch(f -> f.label().equalsIgnoreCase(label))) {
            throw new InvalidQueryException("A field called \"" + label + "\" already exists; choose another name");
        }
        return CalculatedFields.compile("sf_check", label, formula, "shared", null, ds, "check", () -> LocalDate.now(clock));
    }

    private SharedCalculatedField visible(long id) {
        Set<Integer> scope = QueryService.analyticsScope();
        return repo.calculatedField(id).filter(c -> scope.contains(c.hospitalId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Calculated field not found"));
    }

    private SharedCalculatedField editable(long id, Tenant t) {
        SharedCalculatedField c = visible(id);
        if (!t.can(Permission.PUBLISH_SHARED, c.hospitalId())) {
            throw Tenant.forbidden("Only users who may share in its hospital can change this field");
        }
        return c;
    }

    private static Map<String, Object> describe(SharedCalculatedField c, Tenant t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.id());
        out.put("key", c.key());
        out.put("label", c.label());
        out.put("formula", c.formula());
        out.put("description", c.description());
        out.put("type", c.resultType());
        out.put("kind", c.aggregate() ? "measure" : "column");
        out.put("active", c.active());
        out.put("hospital", t.hospital(c.hospitalId()).map(h -> h.code()).orElse(null));
        out.put("owner", c.createdByName());
        out.put("version", c.version());
        out.put("canEdit", t.can(Permission.PUBLISH_SHARED, c.hospitalId()));
        return out;
    }

    private static String label(String label) {
        String l = label == null ? "" : label.trim();
        if (l.isEmpty() || l.length() > 60) {
            throw new InvalidQueryException("Give the field a name of 1-60 characters");
        }
        return l;
    }

    private static String description(String d) {
        if (d == null || d.isBlank()) {
            return null;
        }
        if (d.trim().length() > 200) {
            throw new InvalidQueryException("The description can have at most 200 characters");
        }
        return d.trim();
    }
}
