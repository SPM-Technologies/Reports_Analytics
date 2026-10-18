package com.hospital.patientreg.registration.web;

import com.hospital.patientreg.registration.service.RegistrationSettings;
import com.hospital.patientreg.registration.model.PatientForm;
import com.hospital.patientreg.registration.service.PatientService;
import com.hospital.patientreg.registration.service.PatientValidator;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.time.LocalDate;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
@RequestMapping("/patient")
public class PatientController {

    private final PatientService patientService;
    private final PatientValidator validator;
    private final AuditTrail audit;
    private final RegistrationSettings settings;

    public PatientController(PatientService patientService, PatientValidator validator, AuditTrail audit,
                             RegistrationSettings settings) {
        this.settings = settings;
        this.patientService = patientService;
        this.validator = validator;
        this.audit = audit;
    }

    @InitBinder("patient")
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true)); // blank -> null
        // generated or derived values, and the hospital (from the signed-in user), never come from the request
        binder.setDisallowedFields("patientId", "hospitalId", "registrationNo", "status", "bmi", "deptName");
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        TenantContext.require().requireHere(Permission.REGISTER);
        PatientForm form = new PatientForm();
        form.setRegistrationDate(LocalDate.now());
        form.setVisitDate(LocalDate.now());
        model.addAttribute("patient", form);
        return formView(model);
    }

    @PostMapping("/save")
    public String save(@ModelAttribute("patient") PatientForm form, BindingResult result,
                       Model model, RedirectAttributes redirect) {
        TenantContext.require().requireHere(Permission.REGISTER);
        validator.validate(form, result);
        if (result.hasErrors()) {
            model.addAttribute("errorCount", result.getErrorCount());
            return formView(model);
        }
        long id = patientService.register(form);
        audit.record("PATIENT_REGISTERED", form.getHospitalId(), form.getRegistrationNo());
        redirect.addFlashAttribute("message",
                "Patient registered successfully. Registration No: " + form.getRegistrationNo());
        return "redirect:/patient/" + id;
    }

    @GetMapping("/{id}")
    public String view(@PathVariable long id, Model model) {
        PatientForm patient = patientService.find(id);
        if (patient == null) {   // also for another hospital's patient: 404, so ids cannot be probed
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found");
        }
        audit.record("PATIENT_VIEWED", patient.getHospitalId(), patient.getRegistrationNo());
        model.addAttribute("p", patient);
        model.addAttribute("hospital", TenantContext.require().hospital(patient.getHospitalId()).orElseThrow());
        model.addAttribute("customFieldDefs", settings.customFields(patient.getHospitalId()));
        return "patient-view";
    }

    private String formView(Model model) {
        int hospital = TenantContext.require().activeHospitalId();
        model.addAttribute("lk", settings.lists(hospital));
        model.addAttribute("hiddenFields", settings.hiddenFields(hospital));
        model.addAttribute("customFields", settings.activeCustomFieldsBySection(hospital));
        model.addAttribute("doctors", settings.activeDoctors(hospital));
        model.addAttribute("departments", patientService.departments());
        return "patient-form";
    }
}
