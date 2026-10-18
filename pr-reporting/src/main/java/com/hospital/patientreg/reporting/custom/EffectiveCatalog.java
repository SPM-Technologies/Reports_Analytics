package com.hospital.patientreg.reporting.custom;

import com.hospital.patientreg.reporting.formula.CalculatedFields;
import com.hospital.patientreg.reporting.query.InvalidQueryException;
import com.hospital.patientreg.reporting.semantic.Dataset;
import com.hospital.patientreg.reporting.semantic.DatasetCatalog;
import com.hospital.patientreg.reporting.semantic.Field;
import com.hospital.patientreg.reporting.semantic.FieldType;
import com.hospital.patientreg.shared.fields.CustomFieldDirectory;
import com.hospital.patientreg.shared.fields.ReportableCustomField;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The dataset as one signed-in user may work with it (stage 5b, architecture 10.2):
 * <ol>
 *   <li>standard fields with each hospital's settings applied per row: own label (one hospital), masked or
 *       hidden values, "administrators only" fields hidden from others; a field hidden in all of the user's
 *       hospitals disappears;</li>
 *   <li>the hospitals' custom form fields switched on for reports ({@code cf_<hospital>_<key>}), each with
 *       values only for rows of its own hospital;</li>
 *   <li>the hospitals' shared calculated fields ({@code sf_<id>}), compiled against the fields above, so a
 *       formula can never read a value the user may not see.</li>
 * </ol>
 * Masking applies to free text only (names, numbers such as a registration number): a masked list value, a
 * yes/no, a number or a date would still give the value away, so such fields are hidden instead.
 * All SQL here is generated from validated identifiers and integers only; nothing comes from a request.
 */
@Component
public class EffectiveCatalog {

    private static final Logger log = LoggerFactory.getLogger(EffectiveCatalog.class);
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{1,29}");
    /** fields computed from another field: the source field's setting applies to them */
    private static final Map<String, String> DERIVED_FROM = Map.of(
            "registration_month", "registration_date",
            "age_band", "age_years");

    private final DatasetCatalog catalog;
    private final CustomisationRepository repo;
    private final ObjectProvider<CustomFieldDirectory> customFields;
    private final Clock clock;

    public EffectiveCatalog(DatasetCatalog catalog, CustomisationRepository repo, ObjectProvider<CustomFieldDirectory> customFields,
                            Clock reportingClock) {
        this.catalog = catalog;
        this.repo = repo;
        this.customFields = customFields;
        this.clock = reportingClock;
    }

    /** Fields computed from another field (they have no setting of their own). */
    public static boolean isDerived(String field) {
        return DERIVED_FROM.containsKey(field);
    }

    public Dataset forUser(String datasetId, Collection<Integer> scope) {
        Dataset base = catalog.find(datasetId).orElseThrow(() -> new InvalidQueryException("Unknown dataset: " + datasetId));
        Tenant t = TenantContext.require();
        List<Integer> hospitals = new ArrayList<>(new TreeSet<>(scope));
        boolean several = hospitals.size() > 1;

        Map<String, List<FieldSetting>> settings = repo.fieldSettings(datasetId, hospitals).stream()
                .collect(Collectors.groupingBy(FieldSetting::field));
        List<Field> fields = new ArrayList<>();
        for (Field f : base.fields()) {
            if (f.isMeasure() || f.multiHospital() || f.hidden()) {
                fields.add(f);
                continue;
            }
            // a derived field follows the field it is derived from (the month of the registration date, the age band)
            String settingKey = DERIVED_FROM.getOrDefault(f.name(), f.name());
            Field applied = apply(f, settings.getOrDefault(settingKey, List.of()), hospitals, t, !settingKey.equals(f.name()));
            if (applied != null) {
                fields.add(applied);
            }
        }

        CustomFieldDirectory directory = customFields.getIfAvailable();
        if ("patients".equals(datasetId) && directory != null) {
            for (ReportableCustomField rf : directory.reportable(hospitals)) {
                Field cf = customField(rf, several, t);
                if (cf != null) {
                    fields.add(cf);
                }
            }
        }

        Dataset ds = new Dataset(base.id(), base.label(), base.description(), base.table(), base.version(),
                base.freshnessColumn(), base.defaultSort(), fields);
        for (SharedCalculatedField c : repo.calculatedFields(datasetId, hospitals, true)) {
            String label = several ? c.label() + " (" + code(t, c.hospitalId()) + ")" : c.label();
            try {
                ds = CalculatedFields.plus(ds, CalculatedFields.compile(c.key(), label, c.formula(), "shared",
                        c.description(), ds, "sf" + c.id(), () -> LocalDate.now(clock)));
            } catch (InvalidQueryException e) {
                // e.g. a field it uses was hidden since: the shared field is left out, reports show why
                log.info("Shared calculated field {} skipped: {}", c.key(), e.getMessage());
            }
        }
        return ds;
    }

    // ---------------------------------------------------------------- standard fields

    private static Field apply(Field f, List<FieldSetting> settings, List<Integer> hospitals, Tenant t, boolean derived) {
        Set<Integer> hidden = new TreeSet<>();
        Set<Integer> masked = new TreeSet<>();
        String label = f.label();
        for (int h : hospitals) {
            FieldSetting s = settings.stream().filter(x -> x.hospitalId() == h).findFirst().orElse(null);
            String v = s == null ? "NORMAL" : s.visibility();
            switch (v) {
                case "HIDDEN" -> hidden.add(h);
                case "ADMIN_ONLY" -> {
                    // administrators: whoever manages the hospital's settings or users there
                    if (!t.can(Permission.MANAGE_SETTINGS, h) && !t.can(Permission.MANAGE_USERS, h)) {
                        hidden.add(h);
                    }
                }
                case "MASKED" -> {
                    // free text only: a field with a short list of values (gender, city, ...) stays recognisable
                    // when masked ("FeXXle"), and numbers and dates cannot be partly shown
                    if (f.type() == FieldType.STRING && !f.suggest()) {
                        masked.add(h);
                    } else {
                        hidden.add(h);
                    }
                }
                default -> { }
            }
            // the source field's own label is not copied onto the derived field (two fields with one label)
            if (!derived && hospitals.size() == 1 && s != null && s.label() != null && !s.label().isBlank()) {
                label = s.label().trim();
            }
        }
        if (hidden.size() == hospitals.size()) {
            return null;
        }
        String base = f.expression() != null ? f.expression() : "`" + f.name() + "`";
        String expr = base;
        if (!masked.isEmpty()) {
            expr = "if(`hospital_id` IN (" + ids(masked) + "), " + mask(base) + ", " + base + ")";
        }
        if (!hidden.isEmpty()) {
            expr = "if(`hospital_id` IN (" + ids(hidden) + "), " + (f.type() == FieldType.STRING ? "''" : "NULL") + ", " + expr + ")";
        }
        if (expr.equals(base) && label.equals(f.label())) {
            return f;
        }
        return f.with(label, expr.equals(base) ? f.expression() : expr);
    }

    /** First and last two characters, the rest X (short values entirely X). */
    static String mask(String x) {
        return "if(lengthUTF8(" + x + ") <= 4, 'XXXX', concat(leftUTF8(" + x + ", 2), repeat('X', toUInt64(lengthUTF8(" + x
                + ") - 4)), rightUTF8(" + x + ", 2)))";
    }

    // ---------------------------------------------------------------- custom fields

    private static Field customField(ReportableCustomField rf, boolean several, Tenant t) {
        if (!KEY.matcher(rf.key()).matches()) {
            return null;
        }
        String name = "cf_" + rf.hospitalId() + "_" + rf.key();
        String label = several ? rf.label() + " (" + code(t, rf.hospitalId()) + ")" : rf.label();
        String value = "`custom_fields`['" + rf.key() + "']";
        String mine = "`hospital_id` = " + rf.hospitalId();
        String description = "Own field of " + code(t, rf.hospitalId());
        // masking is for free text only; any other masked field is left out of reports (see the class comment)
        return switch (rf.type()) {
            case "NUMBER", "DECIMAL" -> rf.masked() ? null : Field.generated(name, label, FieldType.NUMBER, "dimension",
                    List.of("sum", "avg", "min", "max"), "if(" + mine + ", toFloat64OrNull(" + value + "), NULL)", false,
                    "custom", null, description, null);
            // Date32: dates before 1970 (e.g. a date of diagnosis) must not turn into 1970-01-01
            case "DATE" -> rf.masked() ? null : Field.generated(name, label, FieldType.DATE, "dimension", null,
                    "if(" + mine + ", toDate32OrNull(" + value + "), NULL)", false, "custom", null, description, null);
            case "YES_NO" -> rf.masked() ? null : Field.generated(name, label, FieldType.BOOLEAN, "dimension", List.of("sum"),
                    "if(" + mine + ", " + value + " = 'true', 0)", false, "custom", null, description, null);
            case "DROPDOWN", "MULTI_SELECT" -> rf.masked() ? null : Field.generated(name, label, FieldType.STRING, "dimension",
                    List.of("countDistinct"), "if(" + mine + ", " + value + ", '')", "DROPDOWN".equals(rf.type()), "custom",
                    null, description, null);
            default -> Field.generated(name, label, FieldType.STRING, "dimension", List.of("countDistinct"),
                    "if(" + mine + ", " + (rf.masked() ? mask(value) : value) + ", '')", false, "custom", null, description, null);
        };
    }

    private static String code(Tenant t, int hospitalId) {
        return t.hospital(hospitalId).map(h -> h.code()).orElse("H" + hospitalId);
    }

    private static String ids(Set<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
