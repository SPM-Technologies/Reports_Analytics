package com.hospital.patientreg.registration.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * A field a hospital added to its registration form (architecture 10.2). Values are stored as text in
 * patient.custom_fields and validated against this definition.
 */
public record CustomFieldDef(int hospitalId, String key, String label, Type type, String section, int sortOrder,
                             boolean required, BigDecimal minValue, BigDecimal maxValue, Integer maxLength,
                             List<String> options, String helpText, boolean reportable, String sensitivity,
                             boolean active, int version) {

    public enum Type {
        TEXT("Text (one line)"), LONG_TEXT("Long text"), NUMBER("Whole number"), DECIMAL("Number with decimals"),
        DATE("Date"), YES_NO("Yes / No"), DROPDOWN("Dropdown (one value)"), MULTI_SELECT("Several values");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public boolean numeric() {
            return this == NUMBER || this == DECIMAL;
        }

        public boolean hasOptions() {
            return this == DROPDOWN || this == MULTI_SELECT;
        }
    }

    /** Form sections a custom field can be placed in (the six standard sections and an extra one). */
    public static final List<String> SECTIONS = List.of("personal", "contact", "emergency", "insurance", "medical", "visit", "additional");

    /** How several values of a multi-select field are stored. */
    public static final String MULTI_SEPARATOR = "; ";

    public CustomFieldDef {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** Longest text accepted (also the default when none is set). */
    public int effectiveMaxLength() {
        int cap = type == Type.LONG_TEXT ? 2000 : 200;
        return maxLength == null ? cap : Math.min(maxLength, cap);
    }

    // getters for JSP EL
    public int getHospitalId() { return hospitalId; }
    public String getKey() { return key; }
    public String getLabel() { return label; }
    public Type getType() { return type; }
    public String getSection() { return section; }
    public int getSortOrder() { return sortOrder; }
    public boolean isRequired() { return required; }
    public BigDecimal getMinValue() { return minValue; }
    public BigDecimal getMaxValue() { return maxValue; }
    public Integer getMaxLength() { return maxLength; }
    public int getEffectiveMaxLength() { return effectiveMaxLength(); }
    public List<String> getOptions() { return options; }
    public String getHelpText() { return helpText; }
    public boolean isReportable() { return reportable; }
    public String getSensitivity() { return sensitivity; }
    public boolean isActive() { return active; }
    public int getVersion() { return version; }
}
