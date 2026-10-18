package com.hospital.patientreg.reporting.saved;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;

/**
 * A saved report: name, description and definition ({"query": ..., "view": ...}).
 * Built-in templates have {@code template = true}, no hospital, and are read-only. A user's report belongs
 * to a hospital; {@code visibility} PRIVATE (its author only) or HOSPITAL (the hospital's report users); {@code canEdit} tells the UI whether the
 * signed-in user may change it (its author or the hospital's administrator).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SavedReport(
        Long id,
        String name,
        String description,
        boolean template,
        LocalDateTime updatedAt,
        JsonNode definition,
        String hospital,
        String owner,
        Boolean canEdit,
        String visibility,
        @JsonIgnore Integer hospitalId,
        @JsonIgnore Long createdBy) {

    SavedReport withAccess(String hospitalLabel, boolean editable) {
        return new SavedReport(id, name, description, template, updatedAt, definition, hospitalLabel, owner, editable,
                visibility, hospitalId, createdBy);
    }
}
