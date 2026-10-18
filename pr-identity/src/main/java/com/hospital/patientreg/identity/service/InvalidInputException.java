package com.hospital.patientreg.identity.service;

/** A user-facing validation message for the administration screens. */
public class InvalidInputException extends RuntimeException {

    public InvalidInputException(String message) {
        super(message);
    }
}
