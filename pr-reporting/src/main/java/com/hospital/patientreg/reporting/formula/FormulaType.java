package com.hospital.patientreg.reporting.formula;

/** Value types of the formula language. */
public enum FormulaType {
    NUMBER, TEXT, DATE, BOOLEAN;

    /** The catalogue type name used by the report API ("number", "string", "date", "boolean"). */
    public String json() {
        return switch (this) {
            case NUMBER -> "number";
            case TEXT -> "string";
            case DATE -> "date";
            case BOOLEAN -> "boolean";
        };
    }
}
