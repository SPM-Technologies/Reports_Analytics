package com.hospital.patientreg.reporting.custom;

import java.time.LocalDateTime;

/** A calculated field shared with a hospital (key in report definitions: {@code sf_<id>}). */
public record SharedCalculatedField(long id, int hospitalId, String datasetId, String label, String formula, String description,
                                    String resultType, boolean aggregate, boolean active, int version, Long createdBy,
                                    String createdByName, LocalDateTime updatedAt) {

    public String key() {
        return "sf_" + id;
    }
}
