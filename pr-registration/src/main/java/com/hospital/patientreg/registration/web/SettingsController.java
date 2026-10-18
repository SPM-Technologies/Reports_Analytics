package com.hospital.patientreg.registration.web;

import com.hospital.patientreg.registration.model.CustomFieldDef;
import com.hospital.patientreg.registration.model.Lookups;
import com.hospital.patientreg.registration.service.CustomFieldInput;
import com.hospital.patientreg.registration.service.RegistrationSettings;
import com.hospital.patientreg.registration.service.SettingsAdminService;
import com.hospital.patientreg.registration.service.SettingsAdminService.InvalidSettingException;
import com.hospital.patientreg.shared.tenant.HospitalAccess;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Settings of the registration form for the ACTIVE hospital (stage 5b, permission MANAGE_SETTINGS):
 * custom fields and hidden optional fields, dropdown lists, departments and doctors.
 */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final RegistrationSettings settings;
    private final SettingsAdminService admin;

    public SettingsController(RegistrationSettings settings, SettingsAdminService admin) {
        this.settings = settings;
        this.admin = admin;
    }

    @InitBinder("field")
    public void initBinder(WebDataBinder binder) {
        binder.setAllowedFields("key", "label", "type", "section", "sortOrder", "required", "minValue", "maxValue", "maxLength",
                "options", "helpText", "reportable", "sensitivity", "active");
    }

    @ModelAttribute
    public void common(Model model) {
        HospitalAccess h = SettingsAdminService.hospital();
        model.addAttribute("settingsHospital", h);
    }

    @GetMapping
    public String index() {
        return "redirect:/settings/fields";
    }

    // ---------------------------------------------------------------- custom fields and hidden fields

    @GetMapping("/fields")
    public String fields(Model model) {
        int h = SettingsAdminService.hospital().hospitalId();
        model.addAttribute("fields", settings.customFields(h));
        model.addAttribute("hideable", Lookups.HIDEABLE);
        model.addAttribute("hidden", settings.hiddenFields(h));
        return "settings/fields";
    }

    @PostMapping("/fields/hidden")
    public String saveHidden(@RequestParam(required = false) List<String> hidden, RedirectAttributes redirect) {
        admin.saveHiddenFields(hidden);
        redirect.addFlashAttribute("message", "Form fields saved.");
        return "redirect:/settings/fields";
    }

    @GetMapping("/fields/new")
    public String newField(Model model) {
        return fieldForm(model, new CustomFieldInput(), null);
    }

    @PostMapping("/fields/new")
    public String createField(@ModelAttribute("field") CustomFieldInput in, Model model, RedirectAttributes redirect) {
        try {
            CustomFieldDef d = admin.saveCustomField(null, in);
            redirect.addFlashAttribute("message", "Field " + d.label() + " added to the form.");
            return "redirect:/settings/fields";
        } catch (InvalidSettingException e) {
            model.addAttribute("error", e.getMessage());
            return fieldForm(model, in, null);
        }
    }

    @GetMapping("/fields/{key}")
    public String editField(@PathVariable String key, Model model) {
        CustomFieldDef d = existing(key);
        CustomFieldInput in = new CustomFieldInput();
        in.setKey(d.key());
        in.setLabel(d.label());
        in.setType(d.type().name());
        in.setSection(d.section());
        in.setSortOrder(d.sortOrder());
        in.setRequired(d.required());
        in.setMinValue(d.minValue() == null ? null : d.minValue().toPlainString());
        in.setMaxValue(d.maxValue() == null ? null : d.maxValue().toPlainString());
        in.setMaxLength(d.maxLength());
        in.setOptions(String.join("\n", d.options()));
        in.setHelpText(d.helpText());
        in.setReportable(d.reportable());
        in.setSensitivity(d.sensitivity());
        in.setActive(d.active());
        return fieldForm(model, in, d);
    }

    @PostMapping("/fields/{key}")
    public String updateField(@PathVariable String key, @ModelAttribute("field") CustomFieldInput in, Model model,
                              RedirectAttributes redirect) {
        CustomFieldDef d = existing(key);
        try {
            admin.saveCustomField(key, in);
            redirect.addFlashAttribute("message", "Field " + in.getLabel() + " saved.");
            return "redirect:/settings/fields";
        } catch (InvalidSettingException e) {
            model.addAttribute("error", e.getMessage());
            in.setKey(key);
            return fieldForm(model, in, d);
        }
    }

    private CustomFieldDef existing(String key) {
        return settings.customField(SettingsAdminService.hospital().hospitalId(), key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found"));
    }

    private String fieldForm(Model model, CustomFieldInput in, CustomFieldDef existing) {
        model.addAttribute("field", in);
        model.addAttribute("existing", existing);
        model.addAttribute("usage", existing == null ? 0 : settings.customFieldUsage(existing.hospitalId(), existing.key()));
        model.addAttribute("types", CustomFieldDef.Type.values());
        model.addAttribute("sections", CustomFieldDef.SECTIONS);
        return "settings/field-form";
    }

    // ---------------------------------------------------------------- lists

    @GetMapping("/lists")
    public String lists(Model model) {
        model.addAttribute("lists", settings.listSettings(SettingsAdminService.hospital().hospitalId()));
        return "settings/lists";
    }

    @PostMapping("/lists/{key}")
    public String saveList(@PathVariable String key, @RequestParam(defaultValue = "") String values, RedirectAttributes redirect) {
        try {
            admin.saveList(key, values);
            redirect.addFlashAttribute("message", Lookups.CUSTOMISABLE.get(key).label() + " saved.");
        } catch (InvalidSettingException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/lists#" + key;
    }

    // ---------------------------------------------------------------- departments and doctors

    @GetMapping("/departments")
    public String departments(Model model) {
        int h = SettingsAdminService.hospital().hospitalId();
        model.addAttribute("departments", settings.departments(h));
        model.addAttribute("doctors", settings.doctorsByDepartment(h));
        return "settings/departments";
    }

    @PostMapping("/departments/new")
    public String addDepartment(@RequestParam String code, @RequestParam String name, RedirectAttributes redirect) {
        try {
            admin.addDepartment(code, name);
            redirect.addFlashAttribute("message", "Department added.");
        } catch (InvalidSettingException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/departments";
    }

    @PostMapping("/departments/{deptId}")
    public String updateDepartment(@PathVariable int deptId, @RequestParam String name,
                                   @RequestParam(defaultValue = "false") boolean active,
                                   @RequestParam(defaultValue = "") String doctors, RedirectAttributes redirect) {
        try {
            admin.updateDepartment(deptId, name, active, doctors);
            redirect.addFlashAttribute("message", "Department saved.");
        } catch (InvalidSettingException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings/departments";
    }
}
