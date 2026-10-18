package com.hospital.patientreg.registration.service;

import com.hospital.patientreg.registration.dao.PatientDao;
import com.hospital.patientreg.registration.dao.ReportDao;
import com.hospital.patientreg.registration.model.ReportFilter;
import com.hospital.patientreg.shared.export.Branding;
import com.hospital.patientreg.shared.export.BrandingProvider;
import com.hospital.patientreg.shared.export.ExcelExporter;
import org.springframework.beans.factory.ObjectProvider;
import com.hospital.patientreg.shared.export.PdfExporter;
import com.hospital.patientreg.shared.export.ReportDefinition;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    public static final String[] REGISTER_HEADERS = {
            "Reg No", "Reg Date", "Patient Name", "Gender", "Age", "Mobile", "City", "State",
            "Department", "Doctor", "Visit Type", "Category", "Insured", "Fee (Rs)"};
    private static final float[] REGISTER_WIDTHS = {14, 10, 24, 7, 5, 11, 12, 13, 18, 18, 10, 10, 7, 9};

    public static final String[] SUMMARY_HEADERS = {
            "Department", "Total", "Male", "Female", "Other", "New", "Follow-up", "Emergency",
            "Insured", "Avg Age", "Total Fee (Rs)"};
    private static final float[] SUMMARY_WIDTHS = {24, 9, 9, 9, 9, 9, 10, 10, 9, 9, 14};

    /** Analytics field behind each register column: the hospital's report-field rules apply here as well. */
    private static final String[] REGISTER_FIELDS = {
            "registration_no", "registration_date", "patient_name", "gender", "age_years", "mobile_masked", "city", "state",
            "dept_name", "consulting_doctor", "visit_type", "patient_category", "is_insured", "registration_fee"};
    private static final String[] SUMMARY_FIELDS = {
            "dept_name", null, "gender", "gender", "gender", "visit_type", "visit_type", "visit_type",
            "is_insured", "age_years", "registration_fee"};
    /** Free-text fields that can be partly shown; any other masked field is hidden (as in Analytics). */
    private static final java.util.Set<String> MASKABLE = java.util.Set.of("registration_no", "patient_name", "mobile_masked");
    private static final int SHOW = 0;
    private static final int MASK = 1;
    private static final int HIDE = 2;

    private final ReportDao reportDao;
    private final PatientDao patientDao;
    private final ExcelExporter excel;
    private final PdfExporter pdf;
    private final ObjectProvider<BrandingProvider> branding;
    private final ObjectProvider<com.hospital.patientreg.shared.fields.ReportFieldRules> fieldRules;

    public ReportService(ReportDao reportDao, PatientDao patientDao, ExcelExporter excel, PdfExporter pdf,
                         ObjectProvider<BrandingProvider> branding,
                         ObjectProvider<com.hospital.patientreg.shared.fields.ReportFieldRules> fieldRules) {
        this.fieldRules = fieldRules;
        this.branding = branding;
        this.reportDao = reportDao;
        this.patientDao = patientDao;
        this.excel = excel;
        this.pdf = pdf;
    }

    // ---------------- screen data (always the user's active hospital) ----------------

    public long registerCount(ReportFilter f) {
        return reportDao.registerCount(f, hospital());
    }

    /** One page; the mobile number is masked for users who may not open patient records. */
    public List<Object[]> registerPage(ReportFilter f) {
        List<Object[]> rows = reportDao.registerPage(f, hospital());
        int[] actions = actions(REGISTER_FIELDS);
        boolean mask = maskContacts();
        rows.forEach(r -> {
            if (mask) {
                r[MOBILE + 1] = maskMobile(r[MOBILE + 1]);   // +1: patient_id comes first on screen
            }
            apply(r, 1, actions);
        });
        return rows;
    }

    public List<Object[]> departmentSummary(ReportFilter f) {
        List<Object[]> rows = reportDao.departmentSummary(f, hospital());
        int[] actions = actions(SUMMARY_FIELDS);
        rows.forEach(r -> apply(r, 0, actions));
        return rows;
    }

    /** What to do with each column under the active hospital's report-field rules. */
    private int[] actions(String[] fields) {
        var provider = fieldRules.getIfAvailable();
        java.util.Map<String, String> rules = provider == null ? java.util.Map.of() : provider.forHospital(hospital());
        boolean admin = TenantContext.require().canHere(Permission.MANAGE_SETTINGS) || TenantContext.require().canHere(Permission.MANAGE_USERS);
        int[] out = new int[fields.length];
        for (int i = 0; i < fields.length; i++) {
            String v = fields[i] == null ? null : rules.get(fields[i]);
            out[i] = v == null || "NORMAL".equals(v) || ("ADMIN_ONLY".equals(v) && admin) ? SHOW
                    : "MASKED".equals(v) && MASKABLE.contains(fields[i]) ? MASK : HIDE;
        }
        return out;
    }

    /** Hides or masks the columns of one row (starting at {@code offset}); a hidden value is shown empty. */
    private static void apply(Object[] row, int offset, int[] actions) {
        for (int i = 0; i < actions.length && offset + i < row.length; i++) {
            if (actions[i] == HIDE) {
                row[offset + i] = null;
            } else if (actions[i] == MASK && row[offset + i] != null) {
                String s = row[offset + i].toString();
                row[offset + i] = s.length() <= 4 ? "XXXX" : s.substring(0, 2) + "X".repeat(s.length() - 4) + s.substring(s.length() - 2);
            }
        }
    }

    /** Position of the mobile number in an export row (Mobile column of REGISTER_HEADERS). */
    private static final int MOBILE = 5;

    private static int hospital() {
        return TenantContext.require().requireHere(Permission.LEGACY_REPORTS).hospitalId();
    }

    private static boolean maskContacts() {
        return !TenantContext.require().canHere(Permission.VIEW_PATIENT);
    }

    static Object maskMobile(Object mobile) {
        String m = mobile == null ? "" : mobile.toString();
        return m.length() < 4 ? m : m.substring(0, 2) + "XXXXXX" + m.substring(m.length() - 2);
    }

    /** Grand total row for the summary: sums counts and fee, weighted average age. */
    public Object[] summaryTotals(List<Object[]> rows) {
        long[] sums = new long[8];
        boolean[] hidden = new boolean[11];   // a column hidden by the hospital's report-field rules has no total
        BigDecimal ageWeighted = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        for (Object[] r : rows) {
            for (int i = 0; i < 8; i++) {
                if (r[i + 1] == null) hidden[i + 1] = true; else sums[i] += (Long) r[i + 1];
            }
            if (r[9] == null) hidden[9] = true; else ageWeighted = ageWeighted.add(((BigDecimal) r[9]).multiply(BigDecimal.valueOf((Long) r[1])));
            if (r[10] == null) hidden[10] = true; else fee = fee.add((BigDecimal) r[10]);
        }
        BigDecimal avgAge = sums[0] == 0 ? BigDecimal.ZERO
                : ageWeighted.divide(BigDecimal.valueOf(sums[0]), 1, RoundingMode.HALF_UP);
        Object[] totals = {"GRAND TOTAL", sums[0], sums[1], sums[2], sums[3], sums[4], sums[5], sums[6],
                sums[7], avgAge, fee};
        for (int i = 1; i < totals.length; i++) {
            if (hidden[i]) totals[i] = null;
        }
        return totals;
    }

    // ---------------- downloads ----------------

    /** Transactional so the PostgreSQL driver can use a cursor and stream rows. */
    @Transactional(readOnly = true)
    public void exportRegister(ReportFilter f, String format, OutputStream out) throws IOException {
        int hospital = hospital();
        boolean mask = maskContacts();
        int[] actions = actions(REGISTER_FIELDS);
        ReportDefinition def = new ReportDefinition(
                "Patient Registration Register - " + TenantContext.require().active().name(),
                f.describe(patientDao.findDepartmentName(f.getDeptId())),
                REGISTER_HEADERS, REGISTER_WIDTHS,
                sink -> reportDao.registerStream(f, hospital, row -> {
                    if (mask) {
                        row[MOBILE] = maskMobile(row[MOBILE]);
                    }
                    apply(row, 0, actions);
                    sink.accept(row);
                }),
                null, branding(format));
        write(def, format, out);
    }

    @Transactional(readOnly = true)
    public void exportSummary(ReportFilter f, String format, OutputStream out) throws IOException {
        List<Object[]> rows = departmentSummary(f);
        ReportDefinition def = new ReportDefinition(
                "Department-wise Registration Summary - " + TenantContext.require().active().name(),
                f.describe(patientDao.findDepartmentName(f.getDeptId())),
                SUMMARY_HEADERS, SUMMARY_WIDTHS,
                rows::forEach,
                summaryTotals(rows), branding(format));
        write(def, format, out);
    }

    /**
     * The active hospital's download look; the hospital may also limit the formats of users without the
     * settings permission (Settings > Downloads).
     */
    /** Largest downloads of the register, as in Analytics (a PDF of lakhs of rows is not readable anyway). */
    static final long MAX_PDF_ROWS = 50_000;
    static final long MAX_EXCEL_ROWS = 1_000_000;

    /**
     * Checks a download before any header is written (so a refusal is never saved as a file): the format must be
     * allowed for the hospital, and the register must fit the format's row limit.
     */
    public void checkDownload(ReportFilter f, String format, boolean register) {
        branding(format);
        if (register) {
            long rows = registerCount(f);
            long max = "pdf".equals(format) ? MAX_PDF_ROWS : MAX_EXCEL_ROWS;
            if (rows > max) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        String.format(java.util.Locale.ROOT, "The register has %,d rows; %s downloads are limited to %,d rows. Add filters%s.",
                                rows, "pdf".equals(format) ? "PDF" : "Excel", max, "pdf".equals(format) ? " or choose Excel" : ""));
            }
        }
    }

    private Branding branding(String format) {
        BrandingProvider provider = branding.getIfAvailable();
        Branding b = provider == null ? Branding.NONE : provider.forHospital(hospital());
        String extension = "pdf".equals(format) ? "pdf" : "xlsx";
        if (!TenantContext.require().canHere(Permission.MANAGE_SETTINGS) && !b.allows(extension)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    extension.toUpperCase() + " downloads are not enabled for this hospital");
        }
        return b;
    }

    private void write(ReportDefinition def, String format, OutputStream out) throws IOException {
        if ("pdf".equals(format)) {
            pdf.export(def, out);
        } else {
            excel.export(def, out);
        }
    }
}
