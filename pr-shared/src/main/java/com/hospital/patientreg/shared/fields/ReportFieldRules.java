package com.hospital.patientreg.shared.fields;

import java.util.Map;

/**
 * A hospital's report-field rules (Settings › Report fields), so that the legacy JSP reports apply the same
 * rules as Analytics. Implemented by the reporting module; the registration module only sees this contract.
 */
public interface ReportFieldRules {

    /** Field name of the Patients dataset (e.g. {@code patient_name}) to NORMAL, MASKED, HIDDEN or ADMIN_ONLY. */
    Map<String, String> forHospital(int hospitalId);
}
