package com.hospital.patientreg.reporting.semantic;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Set;

/** Data type of a catalogue field; decides which filter operators and aggregations are allowed. */
public enum FieldType {
    STRING(Set.of("eq", "neq", "in", "notIn", "contains", "startsWith")),
    NUMBER(Set.of("eq", "neq", "in", "gt", "gte", "lt", "lte", "between")),
    MONEY(Set.of("eq", "neq", "gt", "gte", "lt", "lte", "between")),
    DATE(Set.of("eq", "gt", "gte", "lt", "lte", "between", "relative")),
    BOOLEAN(Set.of("isTrue", "isFalse"));

    private final Set<String> operators;

    FieldType(Set<String> operators) {
        this.operators = operators;
    }

    public boolean allows(String operator) {
        return operators.contains(operator);
    }

    public Set<String> operators() {
        return operators;
    }

    @JsonCreator
    public static FieldType of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
