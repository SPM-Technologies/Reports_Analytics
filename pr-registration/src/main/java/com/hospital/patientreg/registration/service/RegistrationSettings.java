package com.hospital.patientreg.registration.service;

import com.hospital.patientreg.registration.dao.SettingsDao;
import com.hospital.patientreg.registration.dao.SettingsDao.LookupRow;
import com.hospital.patientreg.registration.model.CustomFieldDef;
import com.hospital.patientreg.registration.model.Doctor;
import com.hospital.patientreg.registration.model.ListSetting;
import com.hospital.patientreg.registration.model.Lookups;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * How one hospital's registration form looks (stage 5b): its dropdown values, hidden optional fields,
 * custom fields and doctors. Read on every form view and save; the settings tables are small.
 */
@Service
public class RegistrationSettings {

    private final SettingsDao dao;

    public RegistrationSettings(SettingsDao dao) {
        this.dao = dao;
    }

    /** Every dropdown list (same keys as {@link Lookups#ALL}) with the hospital's own active values where it has them. */
    public Map<String, List<String>> lists(int hospitalId) {
        Map<String, List<String>> out = new LinkedHashMap<>(Lookups.ALL);
        Map<String, List<String>> own = new LinkedHashMap<>();
        for (LookupRow r : dao.lookups(hospitalId)) {
            if (Lookups.CUSTOMISABLE.containsKey(r.listKey())) {
                List<String> values = own.computeIfAbsent(r.listKey(), k -> new ArrayList<>());
                if (r.active()) {
                    values.add(r.value());
                }
            }
        }
        own.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    /** The customisable lists for the settings screen. */
    public List<ListSetting> listSettings(int hospitalId) {
        Map<String, List<LookupRow>> byKey = new LinkedHashMap<>();
        dao.lookups(hospitalId).forEach(r -> byKey.computeIfAbsent(r.listKey(), k -> new ArrayList<>()).add(r));
        List<ListSetting> out = new ArrayList<>();
        Lookups.CUSTOMISABLE.forEach((key, meta) -> {
            List<LookupRow> rows = byKey.get(key);
            if (rows == null) {
                out.add(new ListSetting(key, meta.label(), meta.maxLength(), Lookups.ALL.get(key), List.of(), true));
            } else {
                out.add(new ListSetting(key, meta.label(), meta.maxLength(),
                        rows.stream().filter(LookupRow::active).map(LookupRow::value).toList(),
                        rows.stream().filter(r -> !r.active()).map(LookupRow::value).toList(), false));
            }
        });
        return out;
    }

    public Set<String> hiddenFields(int hospitalId) {
        return dao.hiddenFields(hospitalId);
    }

    /** All custom fields of the hospital, active and inactive. */
    public List<CustomFieldDef> customFields(int hospitalId) {
        return dao.customFields(List.of(hospitalId));
    }

    /** Active custom fields by form section, in order (for the registration form). */
    public Map<String, List<CustomFieldDef>> activeCustomFieldsBySection(int hospitalId) {
        Map<String, List<CustomFieldDef>> out = new LinkedHashMap<>();
        CustomFieldDef.SECTIONS.forEach(s -> out.put(s, new ArrayList<>()));
        customFields(hospitalId).stream().filter(CustomFieldDef::active)
                .sorted((a, b) -> a.sortOrder() != b.sortOrder() ? Integer.compare(a.sortOrder(), b.sortOrder()) : a.label().compareTo(b.label()))
                .forEach(f -> out.get(f.section()).add(f));
        return out;
    }

    public java.util.Optional<CustomFieldDef> customField(int hospitalId, String key) {
        return dao.customField(hospitalId, key);
    }

    /** How many patients have a value for the field. */
    public long customFieldUsage(int hospitalId, String key) {
        return dao.customFieldUsage(hospitalId, key);
    }

    /** All departments of the hospital, active and inactive (settings screen). */
    public List<SettingsDao.DepartmentRow> departments(int hospitalId) {
        return dao.departments(hospitalId);
    }

    /** Active doctors of each department as one name per line (settings screen). */
    public Map<Integer, String> doctorsByDepartment(int hospitalId) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Doctor d : activeDoctors(hospitalId)) {
            out.merge(d.deptId(), d.name(), (a, b) -> a + "\n" + b);
        }
        return out;
    }

    public List<Doctor> activeDoctors(int hospitalId) {
        return dao.doctors(hospitalId).stream().filter(Doctor::active).toList();
    }

    public Map<String, String> customValues(long patientId) {
        return dao.customValues(patientId);
    }
}
