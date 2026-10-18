package com.hospital.patientreg.identity.web;

import com.hospital.patientreg.identity.model.RoleDef;
import com.hospital.patientreg.identity.service.InvalidInputException;
import com.hospital.patientreg.identity.service.RoleAdminService;
import com.hospital.patientreg.identity.service.UserAdminService;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Roles screen: built-in roles (read-only) and each hospital's own roles made of permissions. */
@Controller
@RequestMapping("/admin/roles")
public class AdminRoleController {

    private final RoleAdminService roles;
    private final UserAdminService users;

    public AdminRoleController(RoleAdminService roles, UserAdminService users) {
        this.roles = roles;
        this.users = users;
    }

    @GetMapping
    public String list(Model model) {
        Tenant t = TenantContext.require();
        model.addAttribute("roles", roles.list(t));
        model.addAttribute("hospitals", users.manageableHospitals(t));
        model.addAttribute("permissions", Permission.values());
        return "admin/roles";
    }

    @GetMapping("/new")
    public String newRole(Model model) {
        return form(model, null, null, List.of());
    }

    @PostMapping("/new")
    public String create(@RequestParam int hospitalId, @RequestParam String label,
                         @RequestParam(required = false) List<String> permissions, Model model, RedirectAttributes redirect) {
        try {
            roles.create(TenantContext.require(), hospitalId, label, permissions);
        } catch (InvalidInputException e) {
            model.addAttribute("error", e.getMessage());
            return form(model, null, label, permissions == null ? List.of() : permissions);
        }
        redirect.addFlashAttribute("message", "Role " + label.trim() + " created.");
        return "redirect:/admin/roles";
    }

    @GetMapping("/{key}")
    public String edit(@PathVariable String key, Model model) {
        RoleDef r = roles.editable(TenantContext.require(), key);
        return form(model, r, r.label(), r.permissions().stream().map(Enum::name).toList());
    }

    @PostMapping("/{key}")
    public String update(@PathVariable String key, @RequestParam String label,
                         @RequestParam(required = false) List<String> permissions, Model model, RedirectAttributes redirect) {
        Tenant t = TenantContext.require();
        RoleDef r = roles.editable(t, key);
        try {
            roles.update(t, key, label, permissions);
        } catch (InvalidInputException e) {
            model.addAttribute("error", e.getMessage());
            return form(model, r, label, permissions == null ? List.of() : permissions);
        }
        redirect.addFlashAttribute("message", "Role " + label.trim() + " saved. Users with this role get the change at once.");
        return "redirect:/admin/roles";
    }

    @PostMapping("/{key}/delete")
    public String delete(@PathVariable String key, RedirectAttributes redirect) {
        try {
            roles.delete(TenantContext.require(), key);
            redirect.addFlashAttribute("message", "Role deleted.");
        } catch (InvalidInputException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/roles";
    }

    private String form(Model model, RoleDef role, String label, List<String> chosen) {
        Tenant t = TenantContext.require();
        model.addAttribute("role", role);
        model.addAttribute("label", label);
        model.addAttribute("chosen", chosen);
        model.addAttribute("hospitals", users.manageableHospitals(t));
        model.addAttribute("permissions", Permission.values());
        return "admin/role-form";
    }
}
