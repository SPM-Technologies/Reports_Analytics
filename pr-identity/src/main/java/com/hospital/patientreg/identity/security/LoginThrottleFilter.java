package com.hospital.patientreg.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Refuses (and audits) a sign-in attempt before the password is even checked when {@link LoginThrottle} blocks it. */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginThrottle throttle;
    private final LoginEvents events;

    public LoginThrottleFilter(LoginThrottle throttle, LoginEvents events) {
        this.throttle = throttle;
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod())
                && (request.getContextPath() + "/login").equals(request.getRequestURI())
                && throttle.blocked(request.getParameter("username"), request.getRemoteAddr())) {
            events.throttled(request);
            response.sendRedirect(request.getContextPath() + "/login?error=throttled");
            return;
        }
        chain.doFilter(request, response);
    }
}
