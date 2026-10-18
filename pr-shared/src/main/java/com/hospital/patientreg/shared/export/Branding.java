package com.hospital.patientreg.shared.export;

import java.util.Set;

/**
 * A hospital's look of its downloads (stage 5b, Settings > Downloads): a title prefix, a header and a footer
 * line, a logo (PNG or JPEG), and the formats users without the settings permission may download.
 */
public record Branding(String titlePrefix, String headerText, String footerText, byte[] logo, Set<String> allowedFormats) {

    public static final Branding NONE = new Branding(null, null, null, null, Set.of("xlsx", "pdf", "csv"));

    public String title(String title) {
        return titlePrefix == null || titlePrefix.isBlank() ? title : titlePrefix.trim() + " - " + title;
    }

    public boolean allows(String format) {
        return allowedFormats == null || allowedFormats.contains(format);
    }
}
