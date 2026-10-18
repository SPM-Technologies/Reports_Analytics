package com.hospital.patientreg.reporting.formula;

import com.hospital.patientreg.reporting.formula.Lexer.Kind;
import com.hospital.patientreg.reporting.formula.Lexer.Token;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The calculated-field language (architecture 10.2): a small, safe expression language, never SQL.
 * A formula is parsed into an expression tree, every function is checked against the allow-list below,
 * every field against what the user may see ({@link FieldResolver}), types are checked, and only then
 * ClickHouse SQL is produced. Text values become bound parameters; numbers and dates are re-printed
 * from their parsed values, so nothing the user typed is ever copied into SQL.
 *
 * <pre>
 *   IF([Age] >= 60, "Senior", "Adult")          per-row (calculated column)
 *   [Fee (Rs)] * 1.18                            per-row
 *   COUNT_IF([Insured]) / COUNT() * 100          total (calculated measure)
 *   BUCKET([Age], 18, 40, 60)                    "below 18", "18 to 39", "40 to 59", "60 and above"
 * </pre>
 */
public final class FormulaCompiler {

    /** Result of compiling one formula. */
    public record Compiled(String sql, FormulaType type, boolean aggregate) {
    }

    private static final int MAX_DEPTH = 40;

    private final List<Token> tokens;
    private final FieldResolver fields;
    private final Map<String, Object> params;
    private final String paramPrefix;
    private final Supplier<LocalDate> today;
    private int p;
    private int depth;
    private int paramCount;

    private FormulaCompiler(String formula, FieldResolver fields, Map<String, Object> params, String paramPrefix,
                            Supplier<LocalDate> today) {
        this.tokens = Lexer.tokens(formula);
        this.fields = fields;
        this.params = params;
        this.paramPrefix = paramPrefix;
        this.today = today;
    }

    /**
     * Compiles {@code formula}. Text literals are added to {@code params} under names starting with
     * {@code paramPrefix} (letters, digits, underscore), which must be unique per formula in one query.
     */
    public static Compiled compile(String formula, FieldResolver fields, Map<String, Object> params, String paramPrefix,
                                   Supplier<LocalDate> today) {
        if (!paramPrefix.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("bad parameter prefix " + paramPrefix);
        }
        FormulaCompiler c = new FormulaCompiler(formula, fields, params, paramPrefix, today);
        Expr e = c.or();
        if (c.peek().kind() != Kind.END) {
            throw new FormulaException("Unexpected '" + c.peek().text() + "'", c.peek().pos());
        }
        return new Compiled(e.sql, e.type, e.aggregate);
    }

    // ---------------------------------------------------------------- expression tree (already SQL)

    private record Expr(String sql, FormulaType type, boolean aggregate, boolean constant, int pos) {
    }

    // ---------------------------------------------------------------- parser (precedence climbing)

    private Expr or() {
        Expr left = and();
        while (keyword("OR")) {
            int pos = next().pos();
            Expr right = and();
            left = logical("or", left, right, pos);
        }
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (keyword("AND")) {
            int pos = next().pos();
            Expr right = not();
            left = logical("and", left, right, pos);
        }
        return left;
    }

    private Expr not() {
        if (keyword("NOT")) {
            int pos = next().pos();
            Expr e = not();
            requireType(e, FormulaType.BOOLEAN, "NOT");
            return new Expr("not(" + e.sql + ")", FormulaType.BOOLEAN, e.aggregate, e.constant, pos);
        }
        return comparison();
    }

    private Expr comparison() {
        Expr left = additive();
        Token t = peek();
        if (t.kind() == Kind.OP && List.of("=", "<>", "<", "<=", ">", ">=").contains(t.text())) {
            next();
            Expr right = additive();
            if (left.type != right.type) {
                throw new FormulaException("Cannot compare " + name(left.type) + " with " + name(right.type), t.pos());
            }
            if (left.type == FormulaType.BOOLEAN && !t.text().equals("=") && !t.text().equals("<>")) {
                throw new FormulaException("Yes/no values can only be compared with = or <>", t.pos());
            }
            String op = t.text().equals("<>") ? "!=" : t.text();
            return combine("(" + left.sql + " " + op + " " + right.sql + ")", FormulaType.BOOLEAN, left, right, t.pos());
        }
        return left;
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (peek().kind() == Kind.OP && "+-&".contains(peek().text())) {
            Token t = next();
            Expr right = multiplicative();
            if (t.text().equals("&")) {
                left = combine("concat(" + text(left) + ", " + text(right) + ")", FormulaType.TEXT, left, right, t.pos());
            } else if (left.type == FormulaType.DATE && right.type == FormulaType.NUMBER) {
                String fn = t.text().equals("+") ? "addDays" : "subtractDays";
                left = combine(fn + "(" + left.sql + ", toInt64(" + right.sql + "))", FormulaType.DATE, left, right, t.pos());
            } else {
                requireType(left, FormulaType.NUMBER, t.text());
                requireType(right, FormulaType.NUMBER, t.text());
                left = combine("(" + left.sql + " " + t.text() + " " + right.sql + ")", FormulaType.NUMBER, left, right, t.pos());
            }
        }
        return left;
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (peek().kind() == Kind.OP && "*/%".contains(peek().text())) {
            Token t = next();
            Expr right = unary();
            requireType(left, FormulaType.NUMBER, t.text());
            requireType(right, FormulaType.NUMBER, t.text());
            // division and remainder by zero give an empty value instead of an error or infinity
            String sql = switch (t.text()) {
                case "/" -> "if(" + right.sql + " = 0, NULL, " + left.sql + " / " + right.sql + ")";
                case "%" -> "if(" + right.sql + " = 0, NULL, " + left.sql + " % " + right.sql + ")";
                default -> "(" + left.sql + " * " + right.sql + ")";
            };
            left = combine(sql, FormulaType.NUMBER, left, right, t.pos());
        }
        return left;
    }

    private Expr unary() {
        if (peek().is(Kind.OP, "-")) {
            int pos = next().pos();
            Expr e = unary();
            requireType(e, FormulaType.NUMBER, "-");
            return new Expr("(-" + e.sql + ")", FormulaType.NUMBER, e.aggregate, e.constant, pos);
        }
        return primary();
    }

    private Expr primary() {
        if (++depth > MAX_DEPTH) {
            throw new FormulaException("The formula is nested too deeply", peek().pos());
        }
        try {
            Token t = next();
            switch (t.kind()) {
                case NUMBER -> {
                    return new Expr(number(t), FormulaType.NUMBER, false, true, t.pos());
                }
                case STRING -> {
                    return new Expr(param(t.text()), FormulaType.TEXT, false, true, t.pos());
                }
                case FIELD -> {
                    return field(t.text(), t.pos());
                }
                case LPAREN -> {
                    Expr e = or();
                    expect(Kind.RPAREN, ")");
                    return e;
                }
                case IDENT -> {
                    String word = Lexer.upper(t.text());
                    if (peek().kind() == Kind.LPAREN) {
                        next();
                        List<Expr> args = new ArrayList<>();
                        if (peek().kind() != Kind.RPAREN) {
                            do {
                                args.add(or());
                            } while (accept(Kind.COMMA));
                        }
                        expect(Kind.RPAREN, ")");
                        return function(word, args, t.pos());
                    }
                    switch (word) {
                        case "TRUE" -> {
                            return new Expr("1", FormulaType.BOOLEAN, false, true, t.pos());
                        }
                        case "FALSE" -> {
                            return new Expr("0", FormulaType.BOOLEAN, false, true, t.pos());
                        }
                        default -> {
                            return field(t.text(), t.pos());   // a plain field name such as age_years
                        }
                    }
                }
                default -> throw new FormulaException(t.kind() == Kind.END ? "The formula ends too early" : "Unexpected '" + t.text() + "'", t.pos());
            }
        } finally {
            depth--;
        }
    }

    private Expr field(String name, int pos) {
        FieldResolver.Resolved f = fields.resolve(name);
        if (f == null) {
            throw new FormulaException("Unknown field [" + name + "]", pos);
        }
        return new Expr(f.sql().contains(" ") ? "(" + f.sql() + ")" : f.sql(), f.type(), f.aggregate(), false, pos);
    }

    // ---------------------------------------------------------------- functions (the allow-list)

    private Expr function(String fn, List<Expr> a, int pos) {
        switch (fn) {
            // ---- totals (aggregates)
            case "COUNT" -> {
                arity(fn, a, 0, 0, pos);
                return agg("count()", FormulaType.NUMBER, pos);
            }
            case "COUNT_IF" -> {
                arity(fn, a, 1, 1, pos);
                rowArg(fn, a, pos);
                requireType(a.get(0), FormulaType.BOOLEAN, fn);
                return agg("countIf(" + a.get(0).sql + ")", FormulaType.NUMBER, pos);
            }
            case "COUNT_DISTINCT" -> {
                arity(fn, a, 1, 1, pos);
                rowArg(fn, a, pos);
                return agg("uniqExact(" + a.get(0).sql + ")", FormulaType.NUMBER, pos);
            }
            case "SUM", "AVG", "MIN", "MAX", "MEDIAN", "STDDEV" -> {
                arity(fn, a, 1, 1, pos);
                rowArg(fn, a, pos);
                Expr x = a.get(0);
                if ((fn.equals("MIN") || fn.equals("MAX")) && x.type == FormulaType.DATE) {
                    return agg(fn.toLowerCase() + "(" + x.sql + ")", FormulaType.DATE, pos);
                }
                requireType(x, FormulaType.NUMBER, fn);
                String sql = switch (fn) {
                    case "SUM" -> "sum(" + x.sql + ")";
                    case "AVG" -> "avg(" + x.sql + ")";
                    case "MIN" -> "min(" + x.sql + ")";
                    case "MAX" -> "max(" + x.sql + ")";
                    case "MEDIAN" -> "quantileExact(0.5)(" + x.sql + ")";
                    default -> "stddevSamp(" + x.sql + ")";
                };
                return agg(sql, FormulaType.NUMBER, pos);
            }
            case "SUM_IF", "AVG_IF" -> {
                arity(fn, a, 2, 2, pos);
                rowArg(fn, a, pos);
                requireType(a.get(0), FormulaType.NUMBER, fn);
                requireType(a.get(1), FormulaType.BOOLEAN, fn);
                return agg((fn.equals("SUM_IF") ? "sumIf(" : "avgIf(") + a.get(0).sql + ", " + a.get(1).sql + ")", FormulaType.NUMBER, pos);
            }
            case "PERCENTILE" -> {
                arity(fn, a, 2, 2, pos);
                rowArg(fn, a, pos);
                requireType(a.get(0), FormulaType.NUMBER, fn);
                BigDecimal q = constantNumber(a.get(1), fn);
                if (q.compareTo(BigDecimal.ZERO) < 0 || q.compareTo(BigDecimal.ONE) > 0) {
                    throw new FormulaException("PERCENTILE needs a fraction between 0 and 1, e.g. 0.9", a.get(1).pos);
                }
                return agg("quantileExact(" + q.toPlainString() + ")(" + a.get(0).sql + ")", FormulaType.NUMBER, pos);
            }
            // ---- logic
            case "IF" -> {
                arity(fn, a, 3, 3, pos);
                requireType(a.get(0), FormulaType.BOOLEAN, fn);
                sameType(fn, a.get(1), a.get(2), pos);
                return combine("if(" + a.get(0).sql + ", " + branch(a.get(1)) + ", " + branch(a.get(2)) + ")", a.get(1).type, a, pos);
            }
            case "IN" -> {
                arity(fn, a, 2, 100, pos);
                List<String> vals = new ArrayList<>();
                for (int i = 1; i < a.size(); i++) {
                    sameType(fn, a.get(0), a.get(i), pos);
                    if (!a.get(i).constant) {
                        throw new FormulaException("IN compares with fixed values only", a.get(i).pos);
                    }
                    vals.add(a.get(i).sql);
                }
                return combine("(" + a.get(0).sql + " IN (" + String.join(", ", vals) + "))", FormulaType.BOOLEAN, a, pos);
            }
            case "SWITCH" -> {
                // SWITCH(value, match1, result1, match2, result2, ..., otherwise)
                if (a.size() < 4 || a.size() % 2 != 0) {
                    throw new FormulaException("SWITCH needs a value, pairs of match and result, and a final 'otherwise' result", pos);
                }
                StringBuilder sql = new StringBuilder("multiIf(");
                FormulaType result = a.get(2).type;
                for (int i = 1; i < a.size() - 1; i += 2) {
                    sameType(fn, a.get(0), a.get(i), pos);
                    if (a.get(i + 1).type != result) {
                        throw new FormulaException("All SWITCH results must have the same type", a.get(i + 1).pos);
                    }
                    sql.append(a.get(0).sql).append(" = ").append(a.get(i).sql).append(", ").append(branch(a.get(i + 1))).append(", ");
                }
                if (a.get(a.size() - 1).type != result) {
                    throw new FormulaException("All SWITCH results must have the same type", a.get(a.size() - 1).pos);
                }
                sql.append(branch(a.get(a.size() - 1))).append(")");
                return combine(sql.toString(), result, a, pos);
            }
            case "BUCKET" -> {
                return bucket(a, pos);
            }
            // ---- numbers
            case "ROUND" -> {
                arity(fn, a, 1, 2, pos);
                requireType(a.get(0), FormulaType.NUMBER, fn);
                int digits = a.size() == 2 ? constantNumber(a.get(1), fn).intValue() : 0;
                if (digits < 0 || digits > 6) {
                    throw new FormulaException("ROUND keeps 0 to 6 decimals", pos);
                }
                return combine("round(" + a.get(0).sql + ", " + digits + ")", FormulaType.NUMBER, a, pos);
            }
            case "ABS" -> {
                arity(fn, a, 1, 1, pos);
                requireType(a.get(0), FormulaType.NUMBER, fn);
                return combine("abs(" + a.get(0).sql + ")", FormulaType.NUMBER, a, pos);
            }
            case "NUMBER" -> {
                arity(fn, a, 1, 1, pos);
                requireType(a.get(0), FormulaType.TEXT, fn);
                return combine("toFloat64OrNull(" + a.get(0).sql + ")", FormulaType.NUMBER, a, pos);
            }
            // ---- text
            case "TEXT" -> {
                arity(fn, a, 1, 1, pos);
                return combine(text(a.get(0)), FormulaType.TEXT, a, pos);
            }
            case "CONCAT" -> {
                arity(fn, a, 2, 20, pos);
                List<String> parts = new ArrayList<>();
                a.forEach(x -> parts.add(text(x)));
                return combine("concat(" + String.join(", ", parts) + ")", FormulaType.TEXT, a, pos);
            }
            case "UPPER", "LOWER", "TRIM", "LEN" -> {
                arity(fn, a, 1, 1, pos);
                requireType(a.get(0), FormulaType.TEXT, fn);
                String sql = switch (fn) {
                    case "UPPER" -> "upperUTF8(" + a.get(0).sql + ")";
                    case "LOWER" -> "lowerUTF8(" + a.get(0).sql + ")";
                    case "TRIM" -> "trimBoth(" + a.get(0).sql + ")";
                    default -> "lengthUTF8(" + a.get(0).sql + ")";
                };
                return combine(sql, fn.equals("LEN") ? FormulaType.NUMBER : FormulaType.TEXT, a, pos);
            }
            case "LEFT", "RIGHT" -> {
                arity(fn, a, 2, 2, pos);
                requireType(a.get(0), FormulaType.TEXT, fn);
                requireType(a.get(1), FormulaType.NUMBER, fn);
                return combine((fn.equals("LEFT") ? "leftUTF8(" : "rightUTF8(") + a.get(0).sql + ", toUInt32(greatest(0, " + a.get(1).sql + ")))",
                        FormulaType.TEXT, a, pos);
            }
            case "CONTAINS", "STARTS_WITH" -> {
                arity(fn, a, 2, 2, pos);
                requireType(a.get(0), FormulaType.TEXT, fn);
                requireType(a.get(1), FormulaType.TEXT, fn);
                String sql = fn.equals("CONTAINS")
                        ? "(positionCaseInsensitiveUTF8(" + a.get(0).sql + ", " + a.get(1).sql + ") > 0)"
                        : "startsWith(lowerUTF8(" + a.get(0).sql + "), lowerUTF8(" + a.get(1).sql + "))";
                return combine(sql, FormulaType.BOOLEAN, a, pos);
            }
            // ---- dates
            case "TODAY" -> {
                arity(fn, a, 0, 0, pos);
                return new Expr("toDate('" + today.get() + "')", FormulaType.DATE, false, true, pos);
            }
            case "DATE" -> {
                arity(fn, a, 1, 1, pos);
                Expr x = a.get(0);
                if (x.type != FormulaType.TEXT || !x.constant) {
                    throw new FormulaException("DATE needs a fixed date written as \"YYYY-MM-DD\"", x.pos);
                }
                // only a written text: a computed one (CONCAT, IF ...) is SQL, not a bound value
                Object text = x.sql.startsWith(":") ? params.get(x.sql.substring(1)) : null;
                if (!(text instanceof String s)) {
                    throw new FormulaException("DATE needs a fixed date written as \"YYYY-MM-DD\"", x.pos);
                }
                try {
                    LocalDate d = LocalDate.parse(s);
                    if (d.getYear() < 1900 || d.getYear() > 2299) {
                        throw new FormulaException("DATE needs a year from 1900 to 2299", x.pos);
                    }
                    // Date32: years before 1970 must not turn into 1970-01-01
                    return new Expr("toDate32('" + d + "')", FormulaType.DATE, false, true, pos);
                } catch (DateTimeParseException e) {
                    throw new FormulaException("DATE needs a date written as \"YYYY-MM-DD\"", x.pos);
                }
            }
            case "YEAR", "QUARTER", "MONTH", "WEEK", "DAY", "WEEKDAY" -> {
                arity(fn, a, 1, 1, pos);
                requireType(a.get(0), FormulaType.DATE, fn);
                String f = switch (fn) {
                    case "YEAR" -> "toYear";
                    case "QUARTER" -> "toQuarter";
                    case "MONTH" -> "toMonth";
                    case "WEEK" -> "toISOWeek";
                    case "DAY" -> "toDayOfMonth";
                    default -> "toDayOfWeek";
                };
                return combine(f + "(" + a.get(0).sql + ")", FormulaType.NUMBER, a, pos);
            }
            case "MONTH_START", "QUARTER_START", "YEAR_START", "WEEK_START" -> {
                arity(fn, a, 1, 1, pos);
                requireType(a.get(0), FormulaType.DATE, fn);
                String f = switch (fn) {
                    case "MONTH_START" -> "toStartOfMonth";
                    case "QUARTER_START" -> "toStartOfQuarter";
                    case "YEAR_START" -> "toStartOfYear";
                    default -> "toMonday";
                };
                return combine(f + "(" + a.get(0).sql + ")", FormulaType.DATE, a, pos);
            }
            case "DAYS_BETWEEN", "MONTHS_BETWEEN", "YEARS_BETWEEN" -> {
                arity(fn, a, 2, 2, pos);
                requireType(a.get(0), FormulaType.DATE, fn);
                requireType(a.get(1), FormulaType.DATE, fn);
                String unit = fn.startsWith("DAYS") ? "day" : fn.startsWith("MONTHS") ? "month" : "year";
                // whole months / years (age), not crossed calendar boundaries: 31 Dec to 1 Jan is 0 years
                String f = unit.equals("day") ? "dateDiff" : "age";
                return combine(f + "('" + unit + "', " + a.get(0).sql + ", " + a.get(1).sql + ")", FormulaType.NUMBER, a, pos);
            }
            default -> throw new FormulaException("Unknown function " + fn + " (see the function list)", pos);
        }
    }

    /** BUCKET(x, b1, b2, ...): ranges with readable labels; whole-number bounds read "18 to 39". */
    private Expr bucket(List<Expr> a, int pos) {
        arity("BUCKET", a, 2, 20, pos);
        requireType(a.get(0), FormulaType.NUMBER, "BUCKET");
        List<BigDecimal> bounds = new ArrayList<>();
        for (int i = 1; i < a.size(); i++) {
            BigDecimal b = constantNumber(a.get(i), "BUCKET");
            if (!bounds.isEmpty() && b.compareTo(bounds.get(bounds.size() - 1)) <= 0) {
                throw new FormulaException("BUCKET limits must increase", a.get(i).pos);
            }
            bounds.add(b);
        }
        boolean whole = bounds.stream().allMatch(b -> b.stripTrailingZeros().scale() <= 0);
        StringBuilder sql = new StringBuilder("multiIf(");
        String x = a.get(0).sql;
        for (int i = 0; i < bounds.size(); i++) {
            String label;
            if (i == 0) {
                label = "below " + plain(bounds.get(0));
            } else {
                label = plain(bounds.get(i - 1)) + " to " + (whole ? plain(bounds.get(i).subtract(BigDecimal.ONE)) : "<" + plain(bounds.get(i)));
            }
            sql.append(x).append(" < ").append(bounds.get(i).toPlainString()).append(", ").append(param(label)).append(", ");
        }
        sql.append(param(plain(bounds.get(bounds.size() - 1)) + " and above")).append(")");
        return combine(sql.toString(), FormulaType.TEXT, a, pos);
    }

    // ---------------------------------------------------------------- helpers

    private Expr agg(String sql, FormulaType type, int pos) {
        return new Expr(sql, type, true, false, pos);
    }

    /** Aggregate functions work on per-row values: a total inside a total is refused. */
    private static void rowArg(String fn, List<Expr> a, int pos) {
        for (Expr x : a) {
            if (x.aggregate) {
                throw new FormulaException(fn + " cannot contain another total (COUNT, SUM, ...)", x.pos);
            }
        }
    }

    private Expr combine(String sql, FormulaType type, Expr l, Expr r, int pos) {
        return combine(sql, type, List.of(l, r), pos);
    }

    /**
     * Per-row values and totals cannot be mixed: [Fee] / COUNT() has no single meaning. Constants mix with both.
     */
    private Expr combine(String sql, FormulaType type, List<Expr> parts, int pos) {
        boolean agg = parts.stream().anyMatch(x -> x.aggregate);
        boolean row = parts.stream().anyMatch(x -> !x.aggregate && !x.constant);
        if (agg && row) {
            throw new FormulaException("A formula cannot mix per-patient values with totals: wrap the field in SUM, AVG, MIN or MAX", pos);
        }
        boolean constant = parts.stream().allMatch(x -> x.constant);
        if (sql.length() > 20_000) {
            throw new FormulaException("The formula is too complex", pos);
        }
        return new Expr(sql, type, agg, constant, pos);
    }

    private Expr logical(String op, Expr l, Expr r, int pos) {
        requireType(l, FormulaType.BOOLEAN, op.toUpperCase());
        requireType(r, FormulaType.BOOLEAN, op.toUpperCase());
        return combine(op + "(" + l.sql + ", " + r.sql + ")", FormulaType.BOOLEAN, l, r, pos);
    }

    private String text(Expr e) {
        return e.type == FormulaType.TEXT ? e.sql : "toString(" + e.sql + ")";
    }

    private void sameType(String fn, Expr a, Expr b, int pos) {
        if (a.type != b.type) {
            throw new FormulaException(fn + ": " + name(a.type) + " and " + name(b.type) + " cannot be mixed", pos);
        }
    }

    private static void requireType(Expr e, FormulaType t, String what) {
        if (e.type != t) {
            throw new FormulaException(what + " needs " + name(t) + " but got " + name(e.type), e.pos);
        }
    }

    private static void arity(String fn, List<Expr> a, int min, int max, int pos) {
        if (a.size() < min || a.size() > max) {
            String n = min == max ? String.valueOf(min) : min + " to " + max;
            throw new FormulaException(fn + " takes " + n + " value(s), not " + a.size(), pos);
        }
    }

    private BigDecimal constantNumber(Expr e, String fn) {
        if (e.type != FormulaType.NUMBER || !e.constant) {
            throw new FormulaException(fn + " needs a fixed number here", e.pos);
        }
        try {
            return new BigDecimal(e.sql.replace("(", "").replace(")", ""));
        } catch (NumberFormatException ex) {
            throw new FormulaException(fn + " needs a plain number here", e.pos);
        }
    }

    private static String number(Token t) {
        try {
            return new BigDecimal(t.text()).toPlainString();   // re-printed from the parsed value
        } catch (NumberFormatException e) {
            throw new FormulaException("Not a valid number: " + t.text(), t.pos());
        }
    }

    /**
     * One result of IF / SWITCH: numbers become Float64, because ClickHouse finds no common type for e.g. a
     * Decimal(10,2) fee and a 0.5 literal (error 386) although both are "numbers" in the formula language.
     */
    private static String branch(Expr e) {
        return e.type == FormulaType.NUMBER ? "toFloat64(" + e.sql + ")" : e.sql;
    }

    private String param(String value) {
        String name = paramPrefix + "_" + (++paramCount);
        params.put(name, value);
        return ":" + name;
    }

    private static String plain(BigDecimal b) {
        return b.stripTrailingZeros().toPlainString();
    }

    private static String name(FormulaType t) {
        return switch (t) {
            case NUMBER -> "a number";
            case TEXT -> "text";
            case DATE -> "a date";
            case BOOLEAN -> "a yes/no value";
        };
    }

    private boolean keyword(String word) {
        return peek().kind() == Kind.IDENT && Lexer.upper(peek().text()).equals(word);
    }

    private Token peek() {
        return tokens.get(p);
    }

    private Token next() {
        return tokens.get(p++);
    }

    private boolean accept(Kind k) {
        if (peek().kind() == k) {
            p++;
            return true;
        }
        return false;
    }

    private void expect(Kind k, String what) {
        if (!accept(k)) {
            throw new FormulaException("Expected " + what, peek().pos());
        }
    }
}
