package com.hospital.patientreg.reporting.semantic;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A reportable dataset: one flat ClickHouse table plus the fields users may work with. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Dataset(
        String id,
        String label,
        String description,
        String table,
        int version,
        String freshnessColumn,
        List<SortSpec> defaultSort,
        List<Field> fields) {

    public record SortSpec(String field, String dir) {
    }

    @JsonIgnore
    public Map<String, Field> fieldMap() {
        return fields.stream().collect(Collectors.toMap(Field::name, Function.identity()));
    }

    public Optional<Field> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }
}
