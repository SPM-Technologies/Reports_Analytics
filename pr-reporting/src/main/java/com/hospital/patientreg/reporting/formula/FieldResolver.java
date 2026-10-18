package com.hospital.patientreg.reporting.formula;

/**
 * Looks up the fields a formula may use: only the fields the signed-in user may see, with masking
 * already applied in their SQL. Also resolves other calculated fields (expanded in place).
 */
@FunctionalInterface
public interface FieldResolver {

    /** A field by its name or its label (case-insensitive), or null when the user has no such field. */
    Resolved resolve(String nameOrLabel);

    /** SQL of the field (a catalogue column expression or an expanded calculation), its type, and whether it is a total. */
    record Resolved(String name, String sql, FormulaType type, boolean aggregate) {
    }
}
