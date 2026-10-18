package com.hospital.patientreg.identity.web;

import com.hospital.patientreg.identity.dao.IdentityDao;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Audit log: hospital administrators see their hospitals' entries, super administrators every entry
 * (including sign-ins, which belong to no hospital).
 */
@Controller
public class AuditController {

    static final List<String> ACTIONS = List.of("LOGIN", "LOGIN_FAILED", "LOGOUT", "HOSPITAL_SWITCHED", "PATIENT_REGISTERED",
            "PATIENT_VIEWED", "REPORT_EXPORTED", "SAVED_REPORT_CHANGED", "USER_CREATED", "USER_UPDATED", "USER_UNLOCKED",
            "PASSWORD_CHANGED", "HOSPITAL_CREATED", "HOSPITAL_UPDATED", "ROLE_CHANGED", "SETTINGS_CHANGED");

    private final IdentityDao dao;

    public AuditController(IdentityDao dao) {
        this.dao = dao;
    }

    @GetMapping("/admin/audit")
    public String audit(@RequestParam(required = false) String action, @RequestParam(required = false) String user,
                        Model model) {
        Tenant t = TenantContext.require();
        List<Integer> scope = t.superAdmin() ? null : List.copyOf(t.requireAnywhere(Permission.MANAGE_USERS));
        String knownAction = action != null && ACTIONS.contains(action) ? action : null;   // List.of rejects contains(null)
        model.addAttribute("entries", dao.auditLog(scope, knownAction, user, 300));
        model.addAttribute("actions", ACTIONS);
        model.addAttribute("action", action);
        model.addAttribute("user", user);
        return "admin/audit";
    }
}
