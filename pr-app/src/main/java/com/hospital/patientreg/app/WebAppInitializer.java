package com.hospital.patientreg.app;

import jakarta.servlet.Filter;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;

/** Replaces web.xml: bootstraps Spring when Tomcat starts the app. */
public class WebAppInitializer extends AbstractAnnotationConfigDispatcherServletInitializer {

    @Override
    protected Class<?>[] getRootConfigClasses() {
        return new Class<?>[] {RootConfig.class};
    }

    @Override
    protected Class<?>[] getServletConfigClasses() {
        return new Class<?>[] {WebConfig.class};
    }

    @Override
    protected String[] getServletMappings() {
        return new String[] {"/"};
    }

    /** Uploads up to 1 MB per file (the logo itself is limited to 200 KB by the settings screen). */
    @Override
    protected void customizeRegistration(jakarta.servlet.ServletRegistration.Dynamic registration) {
        registration.setMultipartConfig(new jakarta.servlet.MultipartConfigElement("", 1_048_576, 2_097_152, 262_144));
    }

    @Override
    protected Filter[] getServletFilters() {
        // UTF-8 first (so sign-in form values decode correctly), then Spring Security (bean in the root context)
        return new Filter[] {new CharacterEncodingFilter("UTF-8", true), new DelegatingFilterProxy("springSecurityFilterChain")};
    }
}
