package com.hospital.patientreg.identity.security;

import com.hospital.patientreg.identity.model.AppUser;
import com.hospital.patientreg.identity.service.AppUserDetailsService;
import com.hospital.patientreg.shared.tenant.HospitalAccess;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * After authentication, builds the request's {@link Tenant} from the signed-in user and the hospital
 * chosen in this session, and exposes it to every module ({@link TenantContext}) and to JSPs
 * (request attribute {@code tenant}). Always cleared at the end of the request.
 * <p>
 * When users, roles or hospitals changed since the session's access was loaded ({@link AccessVersion}),
 * the user is reloaded first; a deactivated user, a user without hospitals, or a user whose password was
 * reset elsewhere ({@code session_epoch}) is signed out at once.
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String ACTIVE_HOSPITAL = "pr.activeHospitalId";
    static final String ACCESS_VERSION = "pr.accessVersion";

    private final AccessVersion version;
    private final AppUserDetailsService users;
    private final SecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public TenantFilter(AccessVersion version, AppUserDetailsService users) {
        this.version = version;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AppUser user) {
            AppUser current = upToDate(user, request, response);
            if (current == null) {
                signedOut(request, response);
                return;
            }
            Tenant tenant = new Tenant(current.userId(), current.getUsername(), current.fullName(), current.superAdmin(),
                    current.hospitals(), activeHospital(request, current), request.getRemoteAddr());
            TenantContext.set(tenant);
            request.setAttribute("tenant", tenant);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /** The session's user, reloaded if access changed meanwhile; null if the user may no longer work. */
    private AppUser upToDate(AppUser user, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession();
        long now = version.current();
        Object loaded = session.getAttribute(ACCESS_VERSION);
        if (loaded == null) {
            // the principal was loaded for this session just now (sign-in or account refresh)
            session.setAttribute(ACCESS_VERSION, now);
            return user.hospitals().isEmpty() ? null : user;
        }
        if (loaded instanceof Long v && v == now) {
            return user.hospitals().isEmpty() ? null : user;
        }
        AppUser fresh;
        try {
            fresh = (AppUser) users.loadUserByUsername(user.getUsername());
        } catch (UsernameNotFoundException e) {
            return null;
        }
        if (!fresh.isEnabled() || fresh.sessionEpoch() != user.sessionEpoch()) {
            return null;   // deactivated, no hospital left, or password reset since this session signed in
        }
        fresh.eraseCredentials();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities()));
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
        session.setAttribute(ACCESS_VERSION, now);
        return fresh;
    }

    private static void signedOut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            response.sendRedirect(request.getContextPath() + "/login?expired");
        }
    }

    /** The hospital chosen in this session if the user still has it, else their first hospital. */
    private static int activeHospital(HttpServletRequest request, AppUser user) {
        HttpSession session = request.getSession();
        Object chosen = session.getAttribute(ACTIVE_HOSPITAL);
        if (chosen instanceof Integer id && user.hospitals().stream().anyMatch(h -> h.hospitalId() == id)) {
            return id;
        }
        int first = user.hospitals().stream().mapToInt(HospitalAccess::hospitalId).findFirst().orElseThrow();
        session.setAttribute(ACTIVE_HOSPITAL, first);
        return first;
    }

    /** Called after sign-in: the principal was just loaded, so the session is current. */
    public static void markLoaded(HttpSession session, AccessVersion version) {
        session.setAttribute(ACCESS_VERSION, version.current());
    }
}
