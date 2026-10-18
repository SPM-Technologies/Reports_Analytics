package com.hospital.patientreg.reporting.custom;

import com.hospital.patientreg.shared.fields.ReportFieldRules;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Publishes the hospitals' report-field rules of the Patients dataset to the legacy reports. */
@Component
public class FieldRulesPublisher implements ReportFieldRules {

    private final CustomisationRepository repo;

    public FieldRulesPublisher(CustomisationRepository repo) {
        this.repo = repo;
    }

    @Override
    public Map<String, String> forHospital(int hospitalId) {
        return repo.fieldSettings("patients", List.of(hospitalId)).stream()
                .collect(Collectors.toMap(FieldSetting::field, FieldSetting::visibility, (a, b) -> a));
    }
}
