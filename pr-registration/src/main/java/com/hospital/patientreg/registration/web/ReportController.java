package com.hospital.patientreg.registration.web;

import com.hospital.patientreg.registration.model.Lookups;
import com.hospital.patientreg.registration.model.ReportFilter;
import com.hospital.patientreg.shared.export.ExportLimiter;
import com.hospital.patientreg.registration.service.PatientService;
import com.hospital.patientreg.registration.service.ReportService;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/reports")
public class ReportController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReportService reportService;
    private final PatientService patientService;
    private final ExportLimiter exportLimiter;
    private final AuditTrail audit;

    public ReportController(ReportService reportService, PatientService patientService,
                            ExportLimiter exportLimiter, AuditTrail audit) {
        this.audit = audit;
        this.reportService = reportService;
        this.patientService = patientService;
        this.exportLimiter = exportLimiter;
    }

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }

    @ModelAttribute
    public void lookups(Model model) {
        TenantContext.require().requireHere(Permission.LEGACY_REPORTS);
        model.addAttribute("canViewPatient", TenantContext.require().canHere(Permission.VIEW_PATIENT));
        model.addAttribute("lk", Lookups.ALL);
        model.addAttribute("departments", patientService.departments());
    }

    // ---------------- Report 1: Patient Registration Register ----------------

    @GetMapping("/register")
    public String register(@ModelAttribute("filter") ReportFilter filter, BindingResult ignored, Model model) {
        filter.setPageSize(50);
        long total = reportService.registerCount(filter);
        int pages = (int) Math.max(1, (total + filter.getPageSize() - 1) / filter.getPageSize());
        if (filter.getPage() > pages) filter.setPage(pages);
        if (filter.getPage() < 1) filter.setPage(1);

        model.addAttribute("headers", ReportService.REGISTER_HEADERS);
        model.addAttribute("rows", reportService.registerPage(filter));
        model.addAttribute("total", total);
        model.addAttribute("pages", pages);
        return "report-register";
    }

    @GetMapping("/register/export")
    public void exportRegister(@ModelAttribute("filter") ReportFilter filter, BindingResult ignored,
                               @RequestParam(defaultValue = "excel") String format,
                               HttpServletResponse response) throws IOException {
        TenantContext.require().requireHere(Permission.EXPORT);
        reportService.checkDownload(filter, format, true);   // before any header: a refusal is shown, not saved
        // headers are set only once a slot is free, so a "busy" reply is not saved as a file
        exportLimiter.run(() -> {
            prepareDownload(response, "patient_register", format);
            reportService.exportRegister(filter, format, response.getOutputStream());
            audit.record("REPORT_EXPORTED", TenantContext.require().activeHospitalId(), "Patient register (" + format + ")");
        });
    }

    // ---------------- Report 2: Department-wise Summary ----------------

    @GetMapping("/summary")
    public String summary(@ModelAttribute("filter") ReportFilter filter, BindingResult ignored, Model model) {
        List<Object[]> rows = reportService.departmentSummary(filter);
        model.addAttribute("headers", ReportService.SUMMARY_HEADERS);
        model.addAttribute("rows", rows);
        model.addAttribute("totals", reportService.summaryTotals(rows));
        return "report-summary";
    }

    @GetMapping("/summary/export")
    public void exportSummary(@ModelAttribute("filter") ReportFilter filter, BindingResult ignored,
                              @RequestParam(defaultValue = "excel") String format,
                              HttpServletResponse response) throws IOException {
        TenantContext.require().requireHere(Permission.EXPORT);
        reportService.checkDownload(filter, format, false);
        exportLimiter.run(() -> {
            prepareDownload(response, "department_summary", format);
            reportService.exportSummary(filter, format, response.getOutputStream());
            audit.record("REPORT_EXPORTED", TenantContext.require().activeHospitalId(), "Department summary (" + format + ")");
        });
    }

    private static void prepareDownload(HttpServletResponse response, String baseName, String format) {
        boolean pdf = "pdf".equals(format);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        response.setContentType(pdf ? "application/pdf" : XLSX);
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + baseName + "_" + stamp + (pdf ? ".pdf" : ".xlsx") + "\"");
    }
}
