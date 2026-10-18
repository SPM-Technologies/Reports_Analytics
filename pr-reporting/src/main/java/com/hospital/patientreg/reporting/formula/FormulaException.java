package com.hospital.patientreg.reporting.formula;

import com.hospital.patientreg.reporting.query.InvalidQueryException;

/** A formula the user can correct: the message says what is wrong and where (1-based character position). */
public class FormulaException extends InvalidQueryException {

    private final int position;

    public FormulaException(String message, int position) {
        super(position > 0 ? message + " (at character " + position + ")" : message);
        this.position = position;
    }

    public int position() {
        return position;
    }
}
