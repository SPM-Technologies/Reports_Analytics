package com.hospital.patientreg.identity.web;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

/** Friendly error page for the identity screens (login, account, administration). */
@ControllerAdvice(basePackages = "com.hospital.patientreg.identity.web")
public class IdentityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IdentityExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ModelAndView handle(Exception ex, HttpServletResponse response) {
        int status = ex instanceof ErrorResponse er ? er.getStatusCode().value() : 500;
        if (status >= 500) {
            log.error("Identity request failed", ex);
        }
        response.setStatus(status);
        ModelAndView mav = new ModelAndView("error");
        mav.addObject("status", status);
        String reason = ex instanceof ResponseStatusException rse ? rse.getReason() : null;
        mav.addObject("message", reason != null ? reason : switch (status) {
            case 404 -> "The page you requested was not found.";
            case 403 -> "You do not have permission for this page.";
            case 400 -> "The request was not valid.";
            default -> "Something went wrong while processing your request.";
        });
        return mav;
    }
}
