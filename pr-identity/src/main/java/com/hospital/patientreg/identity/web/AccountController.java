package com.hospital.patientreg.identity.web;

import com.hospital.patientreg.identity.security.PrincipalRefresher;
import com.hospital.patientreg.identity.security.TenantFilter;
import com.hospital.patientreg.identity.service.InvalidInputException;
import com.hospital.patientreg.identity.service.UserAdminService;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.HospitalAccess;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The signed-in user's own settings: working hospital and password. */
@Controller
public class AccountController {

    private final UserAdminService users;
    private final AuditTrail audit;
    private final PrincipalRefresher refresher;

    public AccountController(UserAdminService users, AuditTrail audit, PrincipalRefresher refresher) {
        this.refresher = refresher;
        this.users = users;
        this.audit = audit;
    }

    /** Switches the hospital the user works in; only hospitals assigned to the user are accepted. */
    @PostMapping("/session/hospital")
    public String switchHospital(@RequestParam int hospitalId, HttpServletRequest request) {
        Tenant t = TenantContext.require();
        HospitalAccess target = t.hospital(hospitalId).orElseThrow(() -> Tenant.forbidden("You are not assigned to that hospital"));
        request.getSession().setAttribute(TenantFilter.ACTIVE_HOSPITAL, target.hospitalId());
        audit.record("HOSPITAL_SWITCHED", target.hospitalId(), target.code());
        return "redirect:/";
    }

    @GetMapping("/account/password")
    public String passwordForm() {
        TenantContext.require();
        return "account-password";
    }

    @PostMapping("/account/password")
    public String changePassword(@RequestParam String current, @RequestParam String password,
                                 @RequestParam String confirm, Model model, RedirectAttributes redirect,
                                 HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        Tenant t = TenantContext.require();
        try {
            if (!password.equals(confirm)) {
                throw new InvalidInputException("The new passwords do not match");
            }
            users.changeOwnPassword(t, current, password);
            refresher.refresh(t.username(), request, response);   // this session continues; others are ended
        } catch (InvalidInputException e) {
            model.addAttribute("error", e.getMessage());
            return "account-password";
        }
        redirect.addFlashAttribute("message", "Your password has been changed.");
        return "redirect:/";
    }
}
