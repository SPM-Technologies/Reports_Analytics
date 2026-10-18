package com.hospital.patientreg.registration.model;

import java.util.List;

/** A customisable dropdown list of one hospital: its values in order, and whether they are the built-in ones. */
public record ListSetting(String key, String label, int maxLength, List<String> values, List<String> inactive, boolean builtIn) {

    // getters for JSP EL
    public String getKey() { return key; }
    public String getLabel() { return label; }
    public int getMaxLength() { return maxLength; }
    public List<String> getValues() { return values; }
    public List<String> getInactive() { return inactive; }
    public boolean isBuiltIn() { return builtIn; }
    public String getText() { return String.join("\n", values); }
}
