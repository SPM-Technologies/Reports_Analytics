package com.hospital.patientreg.identity.web;

import com.hospital.patientreg.identity.model.Hospital;
import com.hospital.patientreg.identity.security.PrincipalRefresher;
import com.hospital.patientreg.identity.service.HospitalAdminService;
import com.hospital.patientreg.identity.service.InvalidInputException;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Hospitals screen (super administrators only). */
@Controller
@RequestMapping("/admin/hospitals")
public class AdminHospitalController {

    private final HospitalAdminService service;
    private final PrincipalRefresher refresher;

    public AdminHospitalController(HospitalAdminService service, PrincipalRefresher refresher) {
        this.service = service;
        this.refresher = refresher;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("hospitals", service.list(TenantContext.require()));
        return "admin/hospitals";
    }

    @GetMapping("/new")
    public String newHospital(Model model) {
        service.list(TenantContext.require());   // permission check
        return "admin/hospital-form";
    }

    @PostMapping("/new")
    public String create(@RequestParam String code, @RequestParam String name, @RequestParam(required = false) String city,
                         Model model, RedirectAttributes redirect, HttpServletRequest request, HttpServletResponse response) {
        Tenant t = TenantContext.require();
        try {
            service.create(t, code, name, city);
        } catch (InvalidInputException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("code", code);
            model.addAttribute("name", name);
            model.addAttribute("city", city);
            return "admin/hospital-form";
        }
        refresher.refresh(t.username(), request, response);   // the creator administers the new hospital at once
        redirect.addFlashAttribute("message", "Hospital " + code.trim().toUpperCase() + " created with the default departments. "
                + "You are its administrator; add its users on the Users screen.");
        return "redirect:/admin/hospitals";
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable int id, Model model) {
        model.addAttribute("hospital", service.get(TenantContext.require(), id));
        return "admin/hospital-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable int id, @RequestParam String name, @RequestParam(required = false) String city,
                         @RequestParam(defaultValue = "false") boolean active, Model model, RedirectAttributes redirect,
                         HttpServletRequest request, HttpServletResponse response) {
        Tenant t = TenantContext.require();
        Hospital h = service.get(t, id);
        try {
            service.update(t, id, name, city, active);
        } catch (InvalidInputException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("hospital", h);
            return "admin/hospital-form";
        }
        refresher.refresh(t.username(), request, response);   // a deactivated hospital disappears from the switcher
        redirect.addFlashAttribute("message", "Hospital " + h.code() + " saved.");
        return "redirect:/admin/hospitals";
    }
}
