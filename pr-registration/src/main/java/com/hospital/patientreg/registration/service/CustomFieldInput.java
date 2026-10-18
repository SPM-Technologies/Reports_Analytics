package com.hospital.patientreg.registration.service;

/** A custom field definition as entered on the settings screen (all text, validated by {@link SettingsAdminService}). */
public class CustomFieldInput {

    private String key;
    private String label;
    private String type = "TEXT";
    private String section = "additional";
    private Integer sortOrder = 0;
    private boolean required;
    private String minValue;
    private String maxValue;
    private Integer maxLength;
    private String options;          // one value per line
    private String helpText;
    private boolean reportable;
    private String sensitivity = "NORMAL";
    private boolean active = true;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public String getMinValue() { return minValue; }
    public void setMinValue(String minValue) { this.minValue = minValue; }
    public String getMaxValue() { return maxValue; }
    public void setMaxValue(String maxValue) { this.maxValue = maxValue; }
    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
    public String getHelpText() { return helpText; }
    public void setHelpText(String helpText) { this.helpText = helpText; }
    public boolean isReportable() { return reportable; }
    public void setReportable(boolean reportable) { this.reportable = reportable; }
    public String getSensitivity() { return sensitivity; }
    public void setSensitivity(String sensitivity) { this.sensitivity = sensitivity; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
