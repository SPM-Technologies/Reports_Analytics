package com.hospital.patientreg.reporting.custom;

import java.util.List;

/** How a standard field appears in one hospital's reports. */
public record FieldSetting(int hospitalId, String field, String label, String visibility) {

    public static final List<String> VISIBILITIES = List.of("NORMAL", "MASKED", "HIDDEN", "ADMIN_ONLY");

    // getters for JSP EL
    public String getField() { return field; }
    public String getLabel() { return label; }
    public String getVisibility() { return visibility; }
}
