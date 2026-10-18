package com.hospital.patientreg.identity.config;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

import com.hospital.patientreg.identity.security.CsrfCookieFilter;
import com.hospital.patientreg.identity.security.LoginEvents;
import com.hospital.patientreg.identity.security.TenantFilter;
import com.hospital.patientreg.identity.security.AccessVersion;
import com.hospital.patientreg.identity.security.LoginThrottle;
import com.hospital.patientreg.identity.security.LoginThrottleFilter;
import com.hospital.patientreg.identity.service.AppUserDetailsService;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Session login for JSP and React alike (architecture D10): one HttpSession, form login against local
 * users, CSRF protection (cookie XSRF-TOKEN, sent back by the React app as header X-XSRF-TOKEN and by JSP
 * forms as a hidden field). The API answers 401 instead of redirecting to the login page.
 * Fine-grained rules (which hospital, which permission) are checked by each module against the Tenant.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginEvents loginEvents, LoginThrottle throttle,
                                                   AccessVersion accessVersion, AppUserDetailsService users) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(antMatcher("/login"), antMatcher("/resources/**"), antMatcher("/app/assets/**")).permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .successHandler(loginEvents)
                .failureHandler(loginEvents)
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .addLogoutHandler(loginEvents)
                .logoutSuccessUrl("/login?logout"))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // plain token (not the BREACH-masked one), so cookie, header and hidden field carry the same value
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), antMatcher("/api/**"))
                // everything else (screens, downloads) goes to the sign-in page, whatever the Accept header
                .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"), AnyRequestMatcher.INSTANCE))
            // security headers also on small pages committed before the response is complete
            .headers(headers -> headers
                // only our own scripts (no inline ones), no framing, no plug-ins; inline styles are used by charts
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' blob:; "
                        + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                        .ReferrerPolicy.SAME_ORIGIN))
                .addObjectPostProcessor(new ObjectPostProcessor<HeaderWriterFilter>() {
                    @Override
                    public <O extends HeaderWriterFilter> O postProcess(O filter) {
                        filter.setShouldWriteHeadersEagerly(true);
                        return filter;
                    }
                }))
            .addFilterBefore(new LoginThrottleFilter(throttle, loginEvents), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .addFilterAfter(new TenantFilter(accessVersion, users), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
