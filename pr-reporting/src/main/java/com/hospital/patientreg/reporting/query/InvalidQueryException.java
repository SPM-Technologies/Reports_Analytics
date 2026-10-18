package com.hospital.patientreg.reporting.query;

/** A report definition the catalogue does not allow; reported to the user as HTTP 400. */
public class InvalidQueryException extends RuntimeException {
    public InvalidQueryException(String message) {
        super(message);
    }
}
