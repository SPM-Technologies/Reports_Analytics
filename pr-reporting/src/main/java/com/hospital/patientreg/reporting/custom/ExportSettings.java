package com.hospital.patientreg.reporting.custom;

import com.hospital.patientreg.shared.export.Branding;
import com.hospital.patientreg.shared.export.BrandingProvider;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** A hospital's download settings (reporting.export_setting), used by every Excel/PDF/CSV download. */
@Component
public class ExportSettings implements BrandingProvider {

    public static final List<String> FORMATS = List.of("xlsx", "pdf", "csv");

    private final NamedParameterJdbcTemplate meta;

    public ExportSettings(@Qualifier("reportingMetaJdbc") NamedParameterJdbcTemplate meta) {
        this.meta = meta;
    }

    @Override
    public Branding forHospital(int hospitalId) {
        return meta.query("""
                SELECT title_prefix, header_text, footer_text, logo, allowed_formats FROM reporting.export_setting
                WHERE hospital_id = :h""", Map.of("h", hospitalId),
                (rs, i) -> new Branding(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBytes(4), formats(rs.getString(5))))
                .stream().findFirst().orElse(Branding.NONE);
    }

    /** Saves the texts and formats; the logo is replaced only when {@code replaceLogo} (null removes it). */
    public void save(int hospitalId, String titlePrefix, String header, String footer, Set<String> formats, boolean replaceLogo,
                     byte[] logo, String logoType) {
        MapSqlParameterSource p = new MapSqlParameterSource("h", hospitalId).addValue("t", titlePrefix).addValue("hd", header)
                .addValue("f", footer).addValue("fm", String.join(",", formats)).addValue("logo", logo).addValue("lt", logoType)
                .addValue("rl", replaceLogo);
        meta.update("""
                INSERT INTO reporting.export_setting (hospital_id, title_prefix, header_text, footer_text, allowed_formats, logo, logo_type, updated_at)
                VALUES (:h, :t, :hd, :f, :fm, CASE WHEN CAST(:rl AS BOOLEAN) THEN CAST(:logo AS BYTEA) END,
                    CASE WHEN CAST(:rl AS BOOLEAN) THEN CAST(:lt AS VARCHAR(20)) END, now())
                ON CONFLICT (hospital_id) DO UPDATE SET title_prefix = EXCLUDED.title_prefix, header_text = EXCLUDED.header_text,
                    footer_text = EXCLUDED.footer_text, allowed_formats = EXCLUDED.allowed_formats,
                    logo = CASE WHEN CAST(:rl AS BOOLEAN) THEN EXCLUDED.logo ELSE reporting.export_setting.logo END,
                    logo_type = CASE WHEN CAST(:rl AS BOOLEAN) THEN EXCLUDED.logo_type ELSE reporting.export_setting.logo_type END,
                    updated_at = now()""", p);
    }

    public String logoType(int hospitalId) {
        List<String> t = meta.queryForList("SELECT logo_type FROM reporting.export_setting WHERE hospital_id = :h AND logo IS NOT NULL",
                Map.of("h", hospitalId), String.class);
        return t.isEmpty() ? null : t.getFirst();
    }

    static Set<String> formats(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) {
            Arrays.stream(csv.split(",")).map(String::trim).filter(FORMATS::contains).forEach(out::add);
        }
        return out;
    }
}
