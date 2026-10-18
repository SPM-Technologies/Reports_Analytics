package com.hospital.patientreg.reporting.formula;

import com.hospital.patientreg.reporting.formula.FormulaCompiler.Compiled;
import com.hospital.patientreg.reporting.semantic.Dataset;
import com.hospital.patientreg.reporting.semantic.Field;
import com.hospital.patientreg.reporting.semantic.FieldType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Turns formulas into catalogue fields: a formula with a total (COUNT, SUM, ...) becomes a measure, any
 * other formula a column that can be shown, filtered, grouped and (numbers) summed. Formulas can use every
 * field the user may see in {@code ds}, including calculated fields defined before them.
 */
public final class CalculatedFields {

    private CalculatedFields() {
    }

    /** Compiles one formula against {@code ds}. {@code paramPrefix} must be unique within one query. */
    public static Field compile(String name, String label, String formula, String origin, String description, Dataset ds,
                                String paramPrefix, Supplier<LocalDate> today) {
        Map<String, Object> params = new LinkedHashMap<>();
        Compiled c = FormulaCompiler.compile(formula, resolver(ds, params), params, paramPrefix, today);
        FieldType type = switch (c.type()) {
            case NUMBER -> FieldType.NUMBER;
            case TEXT -> FieldType.STRING;
            case DATE -> FieldType.DATE;
            case BOOLEAN -> FieldType.BOOLEAN;
        };
        if (c.aggregate()) {
            return Field.generated(name, label, type, "measure", null, c.sql(), false, origin, formula, description, params);
        }
        List<String> aggregations = switch (type) {
            case NUMBER -> List.of("sum", "avg", "min", "max");
            case BOOLEAN -> List.of("sum");
            default -> List.of("countDistinct");
        };
        return Field.generated(name, label, type, "dimension", aggregations, c.sql(), type == FieldType.STRING, origin,
                formula, description, params);
    }

    /** The dataset with {@code field} added (or replacing a field of the same name). */
    public static Dataset plus(Dataset ds, Field field) {
        List<Field> fields = new ArrayList<>(ds.fields().stream().filter(f -> !f.name().equals(field.name())).toList());
        fields.add(field);
        return new Dataset(ds.id(), ds.label(), ds.description(), ds.table(), ds.version(), ds.freshnessColumn(),
                ds.defaultSort(), fields);
    }

    /**
     * The formula with every {@code [Label]} replaced by {@code [field_name]}: labels differ between viewers (hospital
     * suffixes, own labels) and can be renamed, names never change. Stored formulas use names; the editor shows labels.
     * Unknown fields are left as written (the check reports them).
     */
    public static String canonical(String formula, Dataset ds) {
        FieldResolver r = resolver(ds, new java.util.HashMap<>());
        StringBuilder out = new StringBuilder(formula);
        List<Lexer.Token> fields = Lexer.tokens(formula).stream().filter(t -> t.kind() == Lexer.Kind.FIELD).toList();
        for (int i = fields.size() - 1; i >= 0; i--) {   // from the end, so earlier positions stay valid
            Lexer.Token t = fields.get(i);
            int start = t.pos() - 1;
            int end = formula.indexOf(']', start);
            FieldResolver.Resolved f = r.resolve(t.text());
            if (f != null && end > start) {
                out.replace(start, end + 1, "[" + f.name() + "]");
            }
        }
        return out.toString();
    }

    /** Fields by name or label (case-insensitive); the parameters of calculated fields used come along. */
    static FieldResolver resolver(Dataset ds, Map<String, Object> params) {
        return nameOrLabel -> {
            String key = nameOrLabel.trim().toLowerCase(Locale.ROOT);
            Field f = ds.fields().stream().filter(x -> x.name().equals(key)).findFirst()
                    .or(() -> ds.fields().stream().filter(x -> x.label().trim().toLowerCase(Locale.ROOT).equals(key)).findFirst())
                    .orElse(null);
            if (f == null || (f.hidden() && !f.name().equals("patient_id"))) {
                return null;
            }
            params.putAll(f.params());
            String sql = f.expression() != null ? f.expression() : "`" + f.name() + "`";
            FormulaType type = switch (f.type()) {
                case NUMBER, MONEY -> FormulaType.NUMBER;
                case STRING -> FormulaType.TEXT;
                case DATE -> FormulaType.DATE;
                case BOOLEAN -> FormulaType.BOOLEAN;
            };
            return new FieldResolver.Resolved(f.name(), sql, type, f.isMeasure());
        };
    }
}
