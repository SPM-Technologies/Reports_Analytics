package com.hospital.patientreg.identity.security;

import com.hospital.patientreg.identity.dao.IdentityDao;
import com.hospital.patientreg.identity.model.AppUser;
import com.hospital.patientreg.identity.service.AppUserDetailsService;
import com.hospital.patientreg.identity.service.JdbcAuditTrail;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/** Sign-in, failed sign-in (throttled per username and address) and sign-out: bookkeeping and audit. */
@Component
public class LoginEvents extends SavedRequestAwareAuthenticationSuccessHandler
        implements AuthenticationFailureHandler, LogoutHandler {

    private final IdentityDao dao;
    private final JdbcAuditTrail audit;
    private final LoginThrottle throttle;
    private final AccessVersion accessVersion;

    public LoginEvents(IdentityDao dao, JdbcAuditTrail audit, LoginThrottle throttle, AccessVersion accessVersion) {
        this.dao = dao;
        this.audit = audit;
        this.throttle = throttle;
        this.accessVersion = accessVersion;
        setDefaultTargetUrl("/");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth)
            throws ServletException, IOException {
        if (auth.getPrincipal() instanceof AppUser user) {
            dao.loginSucceeded(user.userId());
            throttle.succeeded(user.getUsername(), request.getRemoteAddr());
            request.getSession().removeAttribute(TenantFilter.ACTIVE_HOSPITAL);   // start in the first hospital
            TenantFilter.markLoaded(request.getSession(), accessVersion);
            audit.recordFor(user.userId(), user.getUsername(), "LOGIN", null, request.getRemoteAddr());
        }
        super.onAuthenticationSuccess(request, response, auth);
    }

    /** A sign-in refused by the throttle before the password was checked (audited like a failure). */
    public void throttled(HttpServletRequest request) {
        String username = AppUserDetailsService.normalise(request.getParameter("username"));
        audit.recordFor(null, username.length() > 40 ? username.substring(0, 40) : username, "LOGIN_FAILED", "throttled",
                request.getRemoteAddr());
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        String username = AppUserDetailsService.normalise(request.getParameter("username"));
        if (username.length() > 40) {
            username = username.substring(0, 40);
        }
        throttle.failed(username, request.getRemoteAddr());
        audit.recordFor(null, username, "LOGIN_FAILED", e instanceof DisabledException ? "inactive" : "bad",
                request.getRemoteAddr());
        // one message for every reason, so the page does not tell which usernames exist
        response.sendRedirect(request.getContextPath() + "/login?error=bad");
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof AppUser user) {
            audit.recordFor(user.userId(), user.getUsername(), "LOGOUT", null, request.getRemoteAddr());
        }
    }
}
