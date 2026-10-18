package com.hospital.patientreg.shared.fields;

import java.util.List;

/**
 * A hospital's own form field that may appear in reports (stage 5b). Published by the registration module,
 * read by reporting through {@link CustomFieldDirectory}. {@code type} is the custom field type name
 * (TEXT, LONG_TEXT, NUMBER, DECIMAL, DATE, YES_NO, DROPDOWN, MULTI_SELECT); {@code masked} fields show only
 * their first and last characters.
 */
public record ReportableCustomField(int hospitalId, String key, String label, String type, boolean masked, List<String> options) {

    public ReportableCustomField {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
