package com.hospital.patientreg.registration.service;

import com.hospital.patientreg.registration.dao.SettingsDao;
import com.hospital.patientreg.registration.model.CustomFieldDef;
import com.hospital.patientreg.shared.fields.CustomFieldDirectory;
import com.hospital.patientreg.shared.fields.ReportableCustomField;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/** Tells the reporting module which custom fields each hospital has switched on for reports. */
@Service
public class ReportableFieldsPublisher implements CustomFieldDirectory {

    private final SettingsDao dao;

    public ReportableFieldsPublisher(SettingsDao dao) {
        this.dao = dao;
    }

    @Override
    public List<ReportableCustomField> reportable(Collection<Integer> hospitalIds) {
        return dao.customFields(List.copyOf(hospitalIds)).stream()
                .filter(f -> f.active() && f.reportable() && !"HIDDEN".equals(f.sensitivity()))
                .map(f -> new ReportableCustomField(f.hospitalId(), f.key(), f.label(), f.type().name(),
                        "MASKED".equals(f.sensitivity()), f.type() == CustomFieldDef.Type.DROPDOWN ? f.options() : List.of()))
                .toList();
    }
}
