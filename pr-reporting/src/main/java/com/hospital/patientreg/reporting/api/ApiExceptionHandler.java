package com.hospital.patientreg.reporting.api;

import com.hospital.patientreg.reporting.formula.FormulaException;
import com.hospital.patientreg.reporting.query.InvalidQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * JSON errors (RFC 9457 problem+json) for the reporting API only. JSP controllers keep their
 * HTML error page (registration module's GlobalExceptionHandler is scoped to its own package).
 */
@RestControllerAdvice(basePackages = "com.hospital.patientreg.reporting.api")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** A formula mistake also says where it is, so the editor can point at it. */
    @ExceptionHandler(FormulaException.class)
    public ProblemDetail formula(FormulaException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Formula not valid", ex.getMessage());
        pd.setProperty("position", ex.position());
        return pd;
    }

    @ExceptionHandler(InvalidQueryException.class)
    public ProblemDetail invalidQuery(InvalidQueryException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid report definition", ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "The request body is not a valid report definition.");
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail analyticsUnavailable(DataAccessException ex) {
        log.error("Analytics query failed", ex);
        // ClickHouse answered with an error ("Code: 386 ..."): the report itself is the problem, not the server.
        // The server's text is not passed on (it contains SQL); a plain explanation per kind of error is.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Code: (\\d+)").matcher(String.valueOf(ex.getMostSpecificCause().getMessage()));
        if (m.find()) {
            return switch (m.group(1)) {
                case "159", "160" -> problem(HttpStatus.UNPROCESSABLE_ENTITY, "Report too large",
                        "The report took too long to calculate. Add filters or fewer groups.");
                case "241", "396", "158" -> problem(HttpStatus.UNPROCESSABLE_ENTITY, "Report too large",
                        "The report needs too much memory or too many rows. Add filters or fewer groups.");
                default -> problem(HttpStatus.BAD_REQUEST, "Report could not be calculated",
                        "The report could not be calculated (error " + m.group(1) + "), e.g. a formula combines values that do not fit together.");
            };
        }
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Reports temporarily unavailable",
                "The reporting database did not answer. Please try again shortly.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception ex) {
        if (ex instanceof ErrorResponse er) {   // Spring MVC request errors keep their own status (400, 405, ...)
            return er.getBody();
        }
        log.error("Reporting API request failed", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error",
                "Something went wrong while running the report.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
