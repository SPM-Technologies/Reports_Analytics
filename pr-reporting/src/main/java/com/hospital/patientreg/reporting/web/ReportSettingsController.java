package com.hospital.patientreg.reporting.web;

import com.hospital.patientreg.reporting.custom.CustomisationRepository;
import com.hospital.patientreg.reporting.custom.ExportSettings;
import com.hospital.patientreg.reporting.custom.FieldSetting;
import com.hospital.patientreg.reporting.semantic.Dataset;
import com.hospital.patientreg.reporting.semantic.DatasetCatalog;
import com.hospital.patientreg.reporting.semantic.Field;
import com.hospital.patientreg.shared.export.Branding;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.HospitalAccess;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Report settings of the ACTIVE hospital (stage 5b, permission MANAGE_SETTINGS): how standard fields
 * appear in its reports, and its downloads (title, header, footer, logo, formats for non-administrators).
 */
@Controller
@RequestMapping("/settings")
public class ReportSettingsController {

    private static final int MAX_LOGO_BYTES = 200 * 1024;
    /** width and height limit: a small file can still decode into a huge bitmap on every download */
    static final int MAX_LOGO_PIXELS = 2000;

    private final DatasetCatalog catalog;
    private final CustomisationRepository repo;
    private final ExportSettings exports;
    private final AuditTrail audit;

    public ReportSettingsController(DatasetCatalog catalog, CustomisationRepository repo, ExportSettings exports, AuditTrail audit) {
        this.catalog = catalog;
        this.repo = repo;
        this.exports = exports;
        this.audit = audit;
    }

    private static HospitalAccess hospital() {
        return TenantContext.require().requireHere(Permission.MANAGE_SETTINGS);
    }

    /** The fields a hospital can configure: shown fields of the dataset (not measures, the hospital or internal ids). */
    private List<Field> configurable(Dataset ds) {
        return ds.fields().stream().filter(f -> !f.isMeasure() && !f.hidden() && !f.multiHospital()
                && !com.hospital.patientreg.reporting.custom.EffectiveCatalog.isDerived(f.name())).toList();   // derived fields follow their source field
    }

    @GetMapping("/report-fields")
    public String reportFields(Model model) {
        HospitalAccess h = hospital();
        Dataset ds = catalog.find("patients").orElseThrow();
        Map<String, FieldSetting> current = new LinkedHashMap<>();
        repo.fieldSettings(ds.id(), List.of(h.hospitalId())).forEach(s -> current.put(s.field(), s));
        model.addAttribute("fields", configurable(ds));
        model.addAttribute("current", current);
        model.addAttribute("visibilities", FieldSetting.VISIBILITIES);
        return "settings/report-fields";
    }

    @PostMapping("/report-fields")
    public String saveReportFields(HttpServletRequest request, RedirectAttributes redirect) {
        HospitalAccess h = hospital();
        Dataset ds = catalog.find("patients").orElseThrow();
        List<String> changes = new java.util.ArrayList<>();
        // check every field first, then save: an error must not leave half of the form saved
        List<String[]> rows = new java.util.ArrayList<>();
        for (Field f : configurable(ds)) {
            String visibility = request.getParameter("visibility_" + f.name());
            String label = request.getParameter("label_" + f.name());
            if (visibility == null || !FieldSetting.VISIBILITIES.contains(visibility)) {
                continue;
            }
            String l = label == null || label.isBlank() ? null : label.trim();
            if (l != null && l.length() > 60) {
                redirect.addFlashAttribute("error", "Labels: at most 60 characters (" + f.label() + "). Nothing was saved.");
                return "redirect:/settings/report-fields";
            }
            rows.add(new String[] {f.name(), l, visibility});
        }
        // every field keeps a name of its own: a label may not be another field's name (formulas use names)
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (Field f : ds.fields()) {
            String own = rows.stream().filter(r -> r[0].equals(f.name()) && r[1] != null).map(r -> r[1]).findFirst().orElse(f.label());
            String previous = names.put(own.toLowerCase(java.util.Locale.ROOT), f.name());
            if (previous != null) {
                redirect.addFlashAttribute("error", "Two fields would be called \"" + own + "\". Choose another label. Nothing was saved.");
                return "redirect:/settings/report-fields";
            }
        }
        repo.saveFieldSettings(h.hospitalId(), ds.id(), rows);
        for (String[] r : rows) {
            if (!"NORMAL".equals(r[2]) || r[1] != null) {
                changes.add(r[0] + "=" + r[2] + (r[1] == null ? "" : " \"" + r[1] + "\""));
            }
        }
        audit.record("SETTINGS_CHANGED", h.hospitalId(), "report fields: " + (changes.isEmpty() ? "all normal" : String.join(", ", changes)));
        redirect.addFlashAttribute("message", "Report fields saved. Reports use them at once.");
        return "redirect:/settings/report-fields";
    }

    @GetMapping("/downloads")
    public String downloads(Model model) {
        HospitalAccess h = hospital();
        Branding b = exports.forHospital(h.hospitalId());
        model.addAttribute("branding", b);
        model.addAttribute("hasLogo", b.logo() != null);
        model.addAttribute("formats", ExportSettings.FORMATS);
        return "settings/downloads";
    }

    @PostMapping("/downloads")
    public String saveDownloads(@RequestParam(required = false) String titlePrefix, @RequestParam(required = false) String headerText,
                                @RequestParam(required = false) String footerText,
                                @RequestParam(required = false) List<String> formats,
                                @RequestParam(required = false) MultipartFile logo,
                                @RequestParam(defaultValue = "false") boolean removeLogo,
                                RedirectAttributes redirect) throws IOException {
        HospitalAccess h = hospital();
        String error = null;
        if (length(titlePrefix) > 80 || length(headerText) > 200 || length(footerText) > 200) {
            error = "Title prefix: at most 80 characters; header and footer: at most 200";
        }
        Set<String> chosen = new LinkedHashSet<>();
        if (formats != null) {
            formats.stream().filter(ExportSettings.FORMATS::contains).forEach(chosen::add);
        }
        byte[] bytes = null;
        String type = null;
        boolean replace = removeLogo;
        if (error == null && logo != null && !logo.isEmpty()) {
            bytes = logo.getBytes();
            type = imageType(bytes);
            if (bytes.length > MAX_LOGO_BYTES) {
                error = "The logo can be at most 200 KB";
            } else if (type == null) {
                error = "The logo must be a PNG or JPEG image";
            } else if (!smallImage(bytes)) {
                error = "The logo must be a readable image of at most " + MAX_LOGO_PIXELS + " x " + MAX_LOGO_PIXELS + " pixels";
            }
            replace = true;
        }
        if (error != null) {
            redirect.addFlashAttribute("error", error);
            return "redirect:/settings/downloads";
        }
        exports.save(h.hospitalId(), blank(titlePrefix), blank(headerText), blank(footerText), chosen, replace,
                removeLogo && bytes == null ? null : bytes, removeLogo && bytes == null ? null : type);
        audit.record("SETTINGS_CHANGED", h.hospitalId(), "downloads: formats " + chosen + (replace ? (bytes == null ? ", logo removed" : ", new logo") : ""));
        redirect.addFlashAttribute("message", "Download settings saved.");
        return "redirect:/settings/downloads";
    }

    /** The hospital's logo, for the settings page. */
    @GetMapping("/downloads/logo")
    public ResponseEntity<byte[]> logo() {
        HospitalAccess h = hospital();
        Branding b = exports.forHospital(h.hospitalId());
        if (b.logo() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No logo");
        }
        String type = imageType(b.logo());
        return ResponseEntity.ok().contentType("png".equals(type) ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG).body(b.logo());
    }

    /** Reads only the image header (no decoding) and checks width and height. */
    static boolean smallImage(byte[] bytes) {
        try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers = javax.imageio.ImageIO.getImageReaders(in);
            if (in == null || !readers.hasNext()) {
                return false;
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return reader.getWidth(0) <= MAX_LOGO_PIXELS && reader.getHeight(0) <= MAX_LOGO_PIXELS;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Recognises PNG and JPEG by their first bytes (the file name and declared type are not trusted). */
    static String imageType(byte[] b) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        return null;
    }

    private static int length(String s) {
        return s == null ? 0 : s.trim().length();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
