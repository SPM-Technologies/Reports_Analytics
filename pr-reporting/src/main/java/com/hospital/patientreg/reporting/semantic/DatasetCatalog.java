package com.hospital.patientreg.reporting.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads every {@code reporting/datasets/*.json} at startup and validates it, so a broken catalogue
 * stops the application instead of failing on the first query. Field and table names are later
 * written into SQL, so they are restricted to plain identifiers here.
 */
@Component
public class DatasetCatalog {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    private final Map<String, Dataset> datasets = new LinkedHashMap<>();

    public DatasetCatalog(ObjectMapper reportingObjectMapper) throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:reporting/datasets/*.json");
        for (Resource file : files) {
            try (InputStream in = file.getInputStream()) {
                Dataset ds = reportingObjectMapper.readValue(in, Dataset.class);
                validate(ds, file.getFilename());
                datasets.put(ds.id(), ds);
            }
        }
        if (datasets.isEmpty()) {
            throw new IllegalStateException("No reporting datasets found under classpath reporting/datasets/");
        }
    }

    public Optional<Dataset> find(String id) {
        return Optional.ofNullable(id == null ? null : datasets.get(id));
    }

    public Collection<Dataset> all() {
        return datasets.values();
    }

    private static void validate(Dataset ds, String file) {
        require(ds.id() != null && IDENTIFIER.matcher(ds.id()).matches(), file, "invalid dataset id");
        require(ds.table() != null && IDENTIFIER.matcher(ds.table()).matches(), file, "invalid table name");
        require(ds.fields() != null && !ds.fields().isEmpty(), file, "no fields");
        for (Field f : ds.fields()) {
            require(f.name() != null && IDENTIFIER.matcher(f.name()).matches(), file, "invalid field name " + f.name());
            require(f.type() != null, file, "field " + f.name() + " has no type");
            require(!f.isMeasure() || f.expression() != null, file, "measure " + f.name() + " needs an expression");
        }
        require(ds.fieldMap().size() == ds.fields().size(), file, "duplicate field names");
        if (ds.defaultSort() != null) {
            ds.defaultSort().forEach(s -> require(ds.field(s.field()).isPresent(), file, "default sort on unknown field " + s.field()));
        }
    }

    private static void require(boolean ok, String file, String message) {
        if (!ok) {
            throw new IllegalStateException("Reporting catalogue " + file + ": " + message);
        }
    }
}
