package com.hospital.patientreg.app;

import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.context.MessageSource;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewResolverRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Servlet context: each module's web package (JSP controllers, REST API controllers and their advice),
 * the JSP view resolver and static resources.
 */
@Configuration
@EnableWebMvc
@ComponentScan(basePackages = {"com.hospital.patientreg.registration.web", "com.hospital.patientreg.reporting.api",
        "com.hospital.patientreg.identity.web", "com.hospital.patientreg.identity.api", "com.hospital.patientreg.reporting.web"},
        includeFilters = @ComponentScan.Filter({Controller.class, ControllerAdvice.class}), useDefaultFilters = false)
public class WebConfig implements WebMvcConfigurer {

    /** File uploads (the hospital logo on Settings > Downloads); limits are set on the servlet (WebAppInitializer). */
    @Bean
    public org.springframework.web.multipart.MultipartResolver multipartResolver() {
        return new org.springframework.web.multipart.support.StandardServletMultipartResolver();
    }

    /** Friendly texts for type-conversion errors (messages.properties). */
    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @Override
    public void configureViewResolvers(ViewResolverRegistry registry) {
        registry.jsp("/WEB-INF/views/", ".jsp");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/resources/**").addResourceLocations("/resources/");

        // React app: plain files in the WAR's /app folder (production); the pr-ui build output on the
        // classpath is only a fallback for tests and IDE runs. Hashed bundles never change: cache for a year.
        registry.addResourceHandler("/app/assets/**")
                .addResourceLocations("/app/assets/", "classpath:/META-INF/resources/app/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic());
        // Everything else under /app: index.html (never cached) for app routes, real 404 for missing files.
        registry.addResourceHandler("/app/**")
                .addResourceLocations("/app/", "classpath:/META-INF/resources/app/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/app", "/app/");
        // the resource handler rejects an empty path before any resolver runs, so serve the root explicitly
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }

    /** Serves existing files; unknown paths without a file extension get index.html (single-page app routes). */
    static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (!resourcePath.isEmpty()) {
                Resource requested = location.createRelative(resourcePath);
                if (requested.exists() && requested.isReadable()) {
                    return requested;
                }
                String last = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
                if (last.contains(".") || resourcePath.startsWith("assets/")) {
                    return null;   // a missing file stays a 404
                }
            }
            Resource index = location.createRelative("index.html");
            return index.exists() ? index : null;
        }
    }

    /** JSON API: dates as ISO strings ("2026-10-15"), not arrays (plain Spring, unlike Boot, does not set this). */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter json) {
                json.getObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            }
        }
    }
}
