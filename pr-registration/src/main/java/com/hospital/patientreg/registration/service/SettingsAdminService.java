package com.hospital.patientreg.registration.service;

import com.hospital.patientreg.registration.dao.SettingsDao;
import com.hospital.patientreg.registration.dao.SettingsDao.DepartmentRow;
import com.hospital.patientreg.registration.model.CustomFieldDef;
import com.hospital.patientreg.registration.model.Lookups;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.HospitalAccess;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes to the registration form of the user's ACTIVE hospital (Settings screens, permission
 * MANAGE_SETTINGS there): dropdown lists, hidden optional fields, departments and doctors, custom fields.
 * Every change is checked, audited (SETTINGS_CHANGED) and takes effect on the next form view.
 */
@Service
public class SettingsAdminService {

    /** A user-facing message for the settings screens. */
    public static class InvalidSettingException extends RuntimeException {
        public InvalidSettingException(String message) {
            super(message);
        }
    }

    private static final Pattern FIELD_KEY = Pattern.compile("[a-z][a-z0-9_]{1,29}");
    private static final Pattern DEPT_CODE = Pattern.compile("[A-Z0-9]{2,10}");
    private static final int MAX_LIST_VALUES = 200;
    private static final int MAX_CUSTOM_FIELDS = 100;

    private final SettingsDao dao;
    private final AuditTrail audit;

    public SettingsAdminService(SettingsDao dao, AuditTrail audit) {
        this.dao = dao;
        this.audit = audit;
    }

    /** The active hospital, if the user may change its settings (403 otherwise). */
    public static HospitalAccess hospital() {
        return TenantContext.require().requireHere(Permission.MANAGE_SETTINGS);
    }

    // ---------------------------------------------------------------- lists

    @Transactional
    public void saveList(String listKey, String text) {
        HospitalAccess h = hospital();
        Lookups.Customisable meta = Lookups.CUSTOMISABLE.get(listKey);
        if (meta == null) {
            throw new InvalidSettingException("This list cannot be changed");
        }
        List<String> values = lines(text);
        if (values.isEmpty()) {
            throw new InvalidSettingException(meta.label() + ": enter at least one value");
        }
        if (values.size() > MAX_LIST_VALUES) {
            throw new InvalidSettingException(meta.label() + ": at most " + MAX_LIST_VALUES + " values");
        }
        for (String v : values) {
            if (v.length() > meta.maxLength()) {
                throw new InvalidSettingException(meta.label() + ": \"" + v + "\" is longer than " + meta.maxLength() + " characters");
            }
        }
        dao.replaceList(h.hospitalId(), listKey, values);
        audit.record("SETTINGS_CHANGED", h.hospitalId(), "list " + listKey + ": " + String.join(" | ", values));
    }

    // ---------------------------------------------------------------- hidden optional fields

    @Transactional
    public void saveHiddenFields(Collection<String> hidden) {
        HospitalAccess h = hospital();
        List<String> chosen = hidden == null ? List.of() : hidden.stream().filter(Lookups.HIDEABLE::containsKey).toList();
        for (String field : Lookups.HIDEABLE.keySet()) {
            dao.setHidden(h.hospitalId(), field, chosen.contains(field));
        }
        audit.record("SETTINGS_CHANGED", h.hospitalId(), "hidden form fields: " + chosen);
    }

    // ---------------------------------------------------------------- departments and doctors

    @Transactional
    public void addDepartment(String code, String name) {
        HospitalAccess h = hospital();
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!DEPT_CODE.matcher(c).matches()) {
            throw new InvalidSettingException("Department code: 2-10 capital letters or digits");
        }
        if (dao.departmentCodeExists(h.hospitalId(), c)) {
            throw new InvalidSettingException("The code " + c + " is already used");
        }
        dao.insertDepartment(h.hospitalId(), c, name(name, "Department name", 100));
        audit.record("SETTINGS_CHANGED", h.hospitalId(), "department added: " + c);
    }

    @Transactional
    public void updateDepartment(int deptId, String name, boolean active, String doctorsText) {
        HospitalAccess h = hospital();
        DepartmentRow before = dao.departments(h.hospitalId()).stream().filter(d -> d.deptId() == deptId).findFirst()
                .orElseThrow(() -> new InvalidSettingException("Department not found"));
        if (!active && dao.departments(h.hospitalId()).stream().filter(DepartmentRow::active).count() <= 1 && before.active()) {
            throw new InvalidSettingException("At least one department must stay active");
        }
        List<String> doctors = lines(doctorsText);
        if (doctors.size() > 200) {
            throw new InvalidSettingException("At most 200 doctors per department");
        }
        for (String d : doctors) {
            if (d.length() > 100) {
                throw new InvalidSettingException("Doctor names: at most 100 characters");
            }
        }
        String newName = name(name, "Department name", 100);
        dao.updateDepartment(h.hospitalId(), deptId, newName, active);
        dao.replaceDoctors(h.hospitalId(), deptId, doctors);
        if (!newName.equals(before.name())) {
            // reports show the department name of each patient: copy the department's patients again (sync)
            dao.republishDepartment(h.hospitalId(), deptId);
        }
        audit.record("SETTINGS_CHANGED", h.hospitalId(), "department " + before.code() + (active ? "" : " (inactive)") + ", doctors " + doctors);
    }

    /**
     * A renamed hospital: its patients get a new version so the reporting copy shows the new name (identity event,
     * same transaction). Row-level security limits this to hospitals of the renaming super administrator.
     */
    @org.springframework.context.event.EventListener
    public void onHospitalRenamed(com.hospital.patientreg.shared.tenant.HospitalRenamedEvent event) {
        dao.republishHospital(event.hospitalId());
    }

    // ---------------------------------------------------------------- custom fields

    /** Creates ({@code existingKey == null}) or changes a custom field of the active hospital. */
    @Transactional
    public CustomFieldDef saveCustomField(String existingKey, CustomFieldInput in) {
        HospitalAccess h = hospital();
        int hospitalId = h.hospitalId();
        String key = existingKey != null ? existingKey : (in.getKey() == null ? "" : in.getKey().trim().toLowerCase(Locale.ROOT));
        CustomFieldDef before = existingKey == null ? null : dao.customField(hospitalId, existingKey)
                .orElseThrow(() -> new InvalidSettingException("Field not found"));
        if (before == null) {
            if (!FIELD_KEY.matcher(key).matches()) {
                throw new InvalidSettingException("Key: 2-30 lower-case letters, digits or _, starting with a letter (e.g. abha_number)");
            }
            if (dao.customField(hospitalId, key).isPresent()) {
                throw new InvalidSettingException("The key " + key + " is already used (fields are never deleted; reactivate it instead)");
            }
            if (dao.customFields(List.of(hospitalId)).size() >= MAX_CUSTOM_FIELDS) {
                throw new InvalidSettingException("A hospital can have at most " + MAX_CUSTOM_FIELDS + " custom fields");
            }
        }
        CustomFieldDef.Type type;
        try {
            type = CustomFieldDef.Type.valueOf(in.getType());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidSettingException("Choose a field type");
        }
        if (before != null && before.type() != type && dao.customFieldUsage(hospitalId, key) > 0) {
            throw new InvalidSettingException("The type cannot change: " + dao.customFieldUsage(hospitalId, key)
                    + " patients already have a value. Deactivate this field and add a new one instead.");
        }
        if (!CustomFieldDef.SECTIONS.contains(in.getSection())) {
            throw new InvalidSettingException("Choose a form section");
        }
        if (!List.of("NORMAL", "MASKED", "HIDDEN").contains(in.getSensitivity())) {
            throw new InvalidSettingException("Choose how the field appears in reports");
        }
        // a masked list value, yes/no, number or date would still give the value away ("PoXXXXve")
        if ("MASKED".equals(in.getSensitivity()) && type != CustomFieldDef.Type.TEXT && type != CustomFieldDef.Type.LONG_TEXT) {
            throw new InvalidSettingException("Masking is for text fields only; choose Hidden for this field");
        }
        BigDecimal min = number(in.getMinValue(), "Minimum");
        BigDecimal max = number(in.getMaxValue(), "Maximum");
        if (!type.numeric() && (min != null || max != null)) {
            throw new InvalidSettingException("Minimum and maximum apply to number fields only");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new InvalidSettingException("Minimum is larger than maximum");
        }
        Integer maxLength = in.getMaxLength();
        if (maxLength != null && (maxLength < 1 || maxLength > 2000 || !(type == CustomFieldDef.Type.TEXT || type == CustomFieldDef.Type.LONG_TEXT))) {
            throw new InvalidSettingException("Maximum length: 1-2000, for text fields only");
        }
        List<String> options = lines(in.getOptions());
        if (type.hasOptions()) {
            if (options.size() < 2 || options.size() > 100) {
                throw new InvalidSettingException("Dropdown fields need 2 to 100 values (one per line)");
            }
            for (String o : options) {
                if (o.length() > 60 || o.contains(",") || o.contains(";")) {
                    throw new InvalidSettingException("Values: at most 60 characters, without , or ;");
                }
            }
        } else {
            options = List.of();
        }
        int order = in.getSortOrder() == null ? 0 : Math.max(0, Math.min(999, in.getSortOrder()));
        String help = in.getHelpText() == null || in.getHelpText().isBlank() ? null : in.getHelpText().trim();
        if (help != null && help.length() > 200) {
            throw new InvalidSettingException("Help text: at most 200 characters");
        }
        CustomFieldDef def = new CustomFieldDef(hospitalId, key, name(in.getLabel(), "Label", 60), type, in.getSection(), order,
                in.isRequired(), min, max, maxLength, options, help, in.isReportable(), in.getSensitivity(), in.isActive(),
                before == null ? 1 : before.version() + 1);
        dao.saveCustomField(def);
        // only fields shown in reports travel to the reporting copy (v_patient_report): when that changes, the
        // patients holding a value are re-published by the one-minute sync, adding or removing the key there
        boolean shownBefore = before != null && before.active() && before.reportable() && !"HIDDEN".equals(before.sensitivity());
        boolean shownNow = def.active() && def.reportable() && !"HIDDEN".equals(def.sensitivity());
        if (shownBefore != shownNow) {
            dao.republishPatientsWith(hospitalId, key);
        }
        audit.record("SETTINGS_CHANGED", hospitalId, (before == null ? "custom field added: " : "custom field changed: ") + key
                + " (" + type + (def.reportable() ? ", in reports " + def.sensitivity() : "") + (def.active() ? "" : ", inactive") + ")");
        return def;
    }

    // ---------------------------------------------------------------- helpers

    /** Non-empty trimmed lines, duplicates removed, order kept. */
    static List<String> lines(String text) {
        if (text == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(Arrays.stream(text.split("\\r?\\n")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList()));
    }

    private static String name(String value, String what, int max) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.length() > max) {
            throw new InvalidSettingException(what + ": 1-" + max + " characters");
        }
        return v;
    }

    private static BigDecimal number(String value, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        BigDecimal n;
        try {
            n = new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidSettingException(what + " is not a number");
        }
        // the column is NUMERIC(14, 4): at most 10 digits before and 4 after the decimal point
        if (n.scale() > 4 || n.abs().compareTo(new BigDecimal("9999999999")) > 0) {
            throw new InvalidSettingException(what + ": at most 10 digits before and 4 after the decimal point");
        }
        return n;
    }
}
