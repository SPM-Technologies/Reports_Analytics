package com.hospital.patientreg.shared.tenant;

/** What a user may do in one hospital. Roles (identity module) are named sets of these. */
public enum Permission {
    /** register new patients */
    REGISTER("Register patients"),
    /** open a patient's full record, including unmasked contact details and Aadhaar */
    VIEW_PATIENT("Open patient records (unmasked)"),
    /** the JSP reports (patient register, department summary) */
    LEGACY_REPORTS("Patient register and department summary"),
    /** the analytics report builder (/app, /api/v1) */
    ANALYTICS("Analytics report builder"),
    /** download reports as Excel, PDF or CSV */
    EXPORT("Download reports"),
    /** share reports, calculated fields and dashboards with everyone in the hospital */
    PUBLISH_SHARED("Share reports, formulas and dashboards"),
    /** form fields, dropdown lists, departments and doctors, report field settings, download settings */
    MANAGE_SETTINGS("Customise forms, lists and report settings"),
    /** manage users and roles of the hospital and read its audit log */
    MANAGE_USERS("Manage users, roles and audit log");

    private final String label;

    Permission(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
