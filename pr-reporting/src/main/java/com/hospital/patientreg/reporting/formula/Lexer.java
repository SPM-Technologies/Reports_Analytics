package com.hospital.patientreg.reporting.formula;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Splits a formula into tokens. Field references are written [Field label] or as a plain field name. */
final class Lexer {

    enum Kind { NUMBER, STRING, IDENT, FIELD, OP, LPAREN, RPAREN, COMMA, END }

    record Token(Kind kind, String text, int pos) {
        boolean is(Kind k, String t) {
            return kind == k && text.equals(t);
        }
    }

    static final int MAX_LENGTH = 2000;
    static final int MAX_TOKENS = 400;

    private Lexer() {
    }

    static List<Token> tokens(String src) {
        if (src == null || src.isBlank()) {
            throw new FormulaException("Enter a formula", 0);
        }
        if (src.length() > MAX_LENGTH) {
            throw new FormulaException("The formula is longer than " + MAX_LENGTH + " characters", 0);
        }
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            int pos = i + 1;
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1)))) {
                int start = i;
                while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
                    i++;
                }
                String n = src.substring(start, i);
                if (n.indexOf('.') != n.lastIndexOf('.') || n.length() > 30) {
                    throw new FormulaException("Not a valid number: " + n, pos);
                }
                out.add(new Token(Kind.NUMBER, n, pos));
            } else if (c == '"' || c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < src.length()) {
                    char d = src.charAt(i);
                    if (d == c) {
                        if (i + 1 < src.length() && src.charAt(i + 1) == c) {   // "" inside "..." is one quote
                            sb.append(c);
                            i += 2;
                            continue;
                        }
                        closed = true;
                        i++;
                        break;
                    }
                    if (d < ' ') {
                        throw new FormulaException("Text values cannot contain line breaks or control characters", i + 1);
                    }
                    sb.append(d);
                    i++;
                }
                if (!closed) {
                    throw new FormulaException("Text value is not closed with " + c, pos);
                }
                if (sb.length() > 200) {
                    throw new FormulaException("Text values can have at most 200 characters", pos);
                }
                out.add(new Token(Kind.STRING, sb.toString(), pos));
            } else if (c == '[') {
                int end = src.indexOf(']', i);
                if (end < 0) {
                    throw new FormulaException("Field name is not closed with ]", pos);
                }
                String name = src.substring(i + 1, end).trim();
                if (name.isEmpty()) {
                    throw new FormulaException("Empty field name []", pos);
                }
                out.add(new Token(Kind.FIELD, name, pos));
                i = end + 1;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                    i++;
                }
                out.add(new Token(Kind.IDENT, src.substring(start, i), pos));
            } else if (c == '(') {
                out.add(new Token(Kind.LPAREN, "(", pos));
                i++;
            } else if (c == ')') {
                out.add(new Token(Kind.RPAREN, ")", pos));
                i++;
            } else if (c == ',') {
                out.add(new Token(Kind.COMMA, ",", pos));
                i++;
            } else {
                String two = i + 1 < src.length() ? src.substring(i, i + 2) : "";
                if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("!=")) {
                    out.add(new Token(Kind.OP, two.equals("!=") ? "<>" : two, pos));
                    i += 2;
                } else if ("+-*/%=<>&".indexOf(c) >= 0) {
                    out.add(new Token(Kind.OP, String.valueOf(c), pos));
                    i++;
                } else {
                    throw new FormulaException("Unexpected character '" + c + "'", pos);
                }
            }
            if (out.size() > MAX_TOKENS) {
                throw new FormulaException("The formula is too complex", pos);
            }
        }
        out.add(new Token(Kind.END, "", src.length() + 1));
        return out;
    }

    static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
