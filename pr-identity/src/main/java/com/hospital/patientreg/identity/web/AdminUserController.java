package com.hospital.patientreg.identity.web;


import com.hospital.patientreg.identity.model.UserSummary;
import com.hospital.patientreg.identity.security.PrincipalRefresher;
import com.hospital.patientreg.identity.service.InvalidInputException;
import com.hospital.patientreg.identity.service.UserAdminService;
import com.hospital.patientreg.identity.service.UserForm;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Users screen: hospital administrators manage their hospitals' users, super administrators all users. */
@Controller
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserAdminService service;
    private final PrincipalRefresher refresher;

    public AdminUserController(UserAdminService service, PrincipalRefresher refresher) {
        this.service = service;
        this.refresher = refresher;
    }

    @InitBinder("user")
    public void initBinder(WebDataBinder binder) {
        binder.setAllowedFields("username", "fullName", "password", "active", "superAdmin", "roles*");
    }

    @GetMapping
    public String list(Model model) {
        Tenant t = TenantContext.require();
        model.addAttribute("hospitals", service.manageableHospitals(t));
        model.addAttribute("users", service.listUsersForDisplay(t));
        model.addAttribute("blocked", service.blockedUsernames());
        return "admin/users";
    }

    @GetMapping("/new")
    public String newUser(Model model) {
        return form(model, new UserForm(), null);
    }

    @PostMapping("/new")
    public String create(@ModelAttribute("user") UserForm form, Model model, RedirectAttributes redirect) {
        Tenant t = TenantContext.require();
        try {
            service.create(t, form);
        } catch (InvalidInputException e) {
            model.addAttribute("error", e.getMessage());
            form.setPassword(null);
            return form(model, form, null);
        }
        redirect.addFlashAttribute("message", "User " + form.getUsername().trim().toLowerCase() + " created.");
        return "redirect:/admin/users";
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable long id, Model model) {
        UserSummary user = service.getUser(TenantContext.require(), id);
        UserForm form = new UserForm();
        form.setUsername(user.username());
        form.setFullName(user.fullName());
        form.setActive(user.active());
        form.setSuperAdmin(user.superAdmin());
        user.assignments().forEach(a -> form.getRoles().put(a.hospitalId(), a.role().key()));
        return form(model, form, user);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable long id, @ModelAttribute("user") UserForm form, Model model,
                         RedirectAttributes redirect, HttpServletRequest request, HttpServletResponse response) {
        Tenant t = TenantContext.require();
        UserSummary user = service.getUser(t, id);
        try {
            service.update(t, id, form);
        } catch (InvalidInputException e) {
            model.addAttribute("error", e.getMessage());
            form.setUsername(user.username());
            form.setPassword(null);
            return form(model, form, user);
        }
        if (id == t.userId()) {
            refresher.refresh(t.username(), request, response);
        }
        redirect.addFlashAttribute("message", "User " + user.username() + " saved.");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/unlock")
    public String unlock(@PathVariable long id, RedirectAttributes redirect) {
        service.unlock(TenantContext.require(), id);
        redirect.addFlashAttribute("message", "Account unlocked.");
        return "redirect:/admin/users/" + id;
    }

    private String form(Model model, UserForm form, UserSummary existing) {
        Tenant t = TenantContext.require();
        model.addAttribute("user", form);
        model.addAttribute("existing", existing);
        model.addAttribute("hospitals", service.manageableHospitals(t));
        model.addAttribute("roles", service.assignableRoles(t));
        model.addAttribute("canEditAccount", existing == null || service.canEditAccount(t, existing));
        model.addAttribute("self", existing != null && existing.userId() == t.userId());
        model.addAttribute("blockedNow", existing != null && service.blockedUsernames().contains(existing.username()));
        return "admin/user-form";
    }
}
