package com.hospital.patientreg.identity.web;

import com.hospital.patientreg.shared.tenant.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The sign-in page; the form posts to /login, which Spring Security handles. */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error, @RequestParam(required = false) String logout,
                        @RequestParam(required = false) String expired, Model model) {
        if (TenantContext.current().isPresent()) {
            return "redirect:/";
        }
        if (error != null) {
            // one message for every failure reason: the page must not reveal which usernames exist
            model.addAttribute("error", "throttled".equals(error)
                    ? "Too many failed attempts from this computer. Try again in 15 minutes, or ask an administrator to unlock the account."
                    : "Wrong username or password, or the account is not active.");
        }
        if (logout != null) {
            model.addAttribute("info", "You have been signed out.");
        }
        if (expired != null) {
            model.addAttribute("info", "Your access was changed by an administrator. Please sign in again.");
        }
        return "login";
    }
}
