package com.hospital.patientreg.registration.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

/** Shows a friendly error page instead of Tomcat's report (which exposes stack traces and SQL). */
@ControllerAdvice(basePackages = "com.hospital.patientreg.registration.web") // JSP controllers only; the API has its own advice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ModelAndView handle(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        // a non-numeric id in the URL (e.g. /patient/abc) is a bad request, not a server error
        int status = ex instanceof ErrorResponse er ? er.getStatusCode().value() : ex instanceof TypeMismatchException ? 400 : 500;
        if (status == 503) {
            log.warn("Rejected (busy): {} {}", request.getMethod(), request.getRequestURI()); // expected, no stack
        } else if (status >= 500) {
            log.error("Request failed: {} {}", request.getMethod(), request.getRequestURI(), ex);
        }
        if (response.isCommitted()) {
            return null; // a download was already streaming; nothing useful can be shown
        }
        response.setStatus(status);
        ModelAndView mav = new ModelAndView("error");
        mav.addObject("status", status);
        // our own ResponseStatusExceptions carry a user-facing reason; Spring's internal ones do not
        String reason = ex instanceof ResponseStatusException rse ? rse.getReason() : null;
        mav.addObject("message", reason != null ? reason : switch (status) {
            case 404 -> "The page you requested was not found.";
            case 400 -> "The request was not valid.";
            case 403 -> "You do not have permission for this page.";
            case 405 -> "This action is not allowed here.";
            default -> "Something went wrong while processing your request. Please try again; "
                    + "if it keeps happening, contact the administrator.";
        });
        return mav;
    }
}
