package com.hospital.patientreg.identity.security;

import com.hospital.patientreg.identity.model.AppUser;
import com.hospital.patientreg.identity.service.AppUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Reloads the signed-in user's hospitals and roles into the session after they changed their own account. */
@Component
public class PrincipalRefresher {

    private final AppUserDetailsService users;
    private final AccessVersion accessVersion;
    private final SecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public PrincipalRefresher(AppUserDetailsService users, AccessVersion accessVersion) {
        this.users = users;
        this.accessVersion = accessVersion;
    }

    public void refresh(String username, HttpServletRequest request, HttpServletResponse response) {
        AppUser fresh = (AppUser) users.loadUserByUsername(username);
        fresh.eraseCredentials();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities()));
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
        TenantFilter.markLoaded(request.getSession(), accessVersion);
    }
}
