package com.hospital.patientreg.reporting.formula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospital.patientreg.reporting.formula.FormulaCompiler.Compiled;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormulaCompilerTest {

    /** Fields the "user" may see: by name or label; mobile is masked; aadhaar does not exist for them. */
    private static final FieldResolver FIELDS = name -> switch (name.toLowerCase()) {
        case "age", "age_years" -> new FieldResolver.Resolved("age_years", "`age_years`", FormulaType.NUMBER, false);
        case "fee (rs)", "registration_fee" -> new FieldResolver.Resolved("registration_fee", "`registration_fee`", FormulaType.NUMBER, false);
        case "city" -> new FieldResolver.Resolved("city", "`city`", FormulaType.TEXT, false);
        case "reg date", "registration_date" -> new FieldResolver.Resolved("registration_date", "`registration_date`", FormulaType.DATE, false);
        case "insured", "is_insured" -> new FieldResolver.Resolved("is_insured", "`is_insured`", FormulaType.BOOLEAN, false);
        case "patients" -> new FieldResolver.Resolved("patients", "count()", FormulaType.NUMBER, true);
        default -> null;
    };

    private final Map<String, Object> params = new LinkedHashMap<>();

    private Compiled compile(String f) {
        return FormulaCompiler.compile(f, FIELDS, params, "f1", () -> LocalDate.of(2026, 10, 16));
    }

    @Test
    void perRowCalculations() {
        Compiled c = compile("IF([Age] >= 60, \"Senior\", \"Adult\")");
        assertThat(c.type()).isEqualTo(FormulaType.TEXT);
        assertThat(c.aggregate()).isFalse();
        assertThat(c.sql()).isEqualTo("if((`age_years` >= 60), :f1_1, :f1_2)");
        assertThat(params).containsEntry("f1_1", "Senior").containsEntry("f1_2", "Adult");

        assertThat(compile("[Fee (Rs)] * 1.18").sql()).isEqualTo("(`registration_fee` * 1.18)");
        assertThat(compile("registration_fee / 2").sql()).isEqualTo("if(2 = 0, NULL, `registration_fee` / 2)");
        assertThat(compile("DAYS_BETWEEN([Reg Date], TODAY())").sql())
                .isEqualTo("dateDiff('day', `registration_date`, toDate('2026-10-16'))");
        assertThat(compile("UPPER([City]) & \" / \" & TEXT([Age])").type()).isEqualTo(FormulaType.TEXT);
        assertThat(compile("[Reg Date] + 30").type()).isEqualTo(FormulaType.DATE);
        assertThat(compile("[Insured] AND [Age] > 18 OR NOT [Insured]").type()).isEqualTo(FormulaType.BOOLEAN);
        assertThat(compile("IN([City], \"Pune\", \"Mumbai\")").sql()).isEqualTo("(`city` IN (:f1_1, :f1_2))");
    }

    @Test
    void totals() {
        Compiled c = compile("COUNT_IF([Insured]) / COUNT() * 100");
        assertThat(c.aggregate()).isTrue();
        assertThat(c.type()).isEqualTo(FormulaType.NUMBER);
        assertThat(compile("SUM([Fee (Rs)]) / [Patients]").aggregate()).isTrue();
        assertThat(compile("PERCENTILE([Age], 0.9)").sql()).isEqualTo("quantileExact(0.9)(`age_years`)");
        assertThat(compile("MEDIAN(age)").sql()).isEqualTo("quantileExact(0.5)(`age_years`)");
        assertThat(compile("ROUND(AVG([Age]), 1)").sql()).isEqualTo("round(avg(`age_years`), 1)");
        assertThat(compile("MAX([Reg Date])").type()).isEqualTo(FormulaType.DATE);
        assertThat(compile("SUM_IF([Fee (Rs)], [City] = \"Pune\")").sql()).startsWith("sumIf(`registration_fee`, (`city` = :f1_");
    }

    @Test
    void bucketsAndGroups() {
        Compiled b = compile("BUCKET([Age], 18, 40, 60)");
        assertThat(b.sql()).isEqualTo("multiIf(`age_years` < 18, :f1_1, `age_years` < 40, :f1_2, `age_years` < 60, :f1_3, :f1_4)");
        assertThat(params.values()).containsExactly("below 18", "18 to 39", "40 to 59", "60 and above");
        params.clear();
        Compiled s = compile("SWITCH([City], \"Pune\", \"West\", \"Mumbai\", \"West\", \"Other\")");
        assertThat(s.sql()).isEqualTo("multiIf(`city` = :f1_1, :f1_2, `city` = :f1_3, :f1_4, :f1_5)");
        assertThatThrownBy(() -> compile("BUCKET([Age], 40, 18)")).hasMessageContaining("must increase");
    }

    @Test
    void textIsNeverCopiedIntoSql() {
        Compiled c = compile("CONTAINS([City], \"x'); DROP TABLE patient_report; --\")");
        assertThat(c.sql()).doesNotContain("DROP").contains(":f1_1");
        assertThat(params).containsEntry("f1_1", "x'); DROP TABLE patient_report; --");
        assertThat(compile("\"say \"\"hi\"\"\" = [City]").sql()).isEqualTo("(:f1_1 = `city`)");   // numbering restarts per formula
        assertThat(params).containsEntry("f1_1", "say \"hi\"");
    }

    @Test
    void mistakesAreExplained() {
        assertThatThrownBy(() -> compile("[Aadhaar] = \"1\"")).hasMessageContaining("Unknown field [Aadhaar]");
        assertThatThrownBy(() -> compile("SLEEP(5)")).hasMessageContaining("Unknown function SLEEP");
        assertThatThrownBy(() -> compile("[Fee (Rs)] / COUNT()")).hasMessageContaining("cannot mix per-patient values with totals");
        assertThatThrownBy(() -> compile("SUM(COUNT())")).hasMessageContaining("cannot contain another total");
        assertThatThrownBy(() -> compile("[City] + 1")).hasMessageContaining("needs a number but got text");
        assertThatThrownBy(() -> compile("[Age] = \"60\"")).hasMessageContaining("Cannot compare a number with text");
        assertThatThrownBy(() -> compile("IF([Age] > 1, 1, \"x\")")).hasMessageContaining("cannot be mixed");
        assertThatThrownBy(() -> compile("ROUND([Age]")).hasMessageContaining("Expected )");
        assertThatThrownBy(() -> compile("[Age] >")).hasMessageContaining("ends too early");
        assertThatThrownBy(() -> compile("\"open")).hasMessageContaining("not closed");
        assertThatThrownBy(() -> compile("PERCENTILE([Age], 2)")).hasMessageContaining("between 0 and 1");
        assertThatThrownBy(() -> compile("PERCENTILE([Age], [Age])")).hasMessageContaining("fixed number");
        assertThatThrownBy(() -> compile("1 ; 2")).hasMessageContaining("Unexpected character ';'");
        assertThatThrownBy(() -> compile("(".repeat(60) + "1" + ")".repeat(60))).hasMessageContaining("nested too deeply");
        assertThatThrownBy(() -> compile("1 +".repeat(900) + "1")).hasMessageContaining("longer than 2000");
        assertThatThrownBy(() -> compile("1+".repeat(450) + "1")).hasMessageContaining("too complex");
        assertThatThrownBy(() -> compile("DATE(\"16-10-2026\")")).hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> compile(" ")).hasMessageContaining("Enter a formula");
    }

    @Test
    void positionsPointAtTheMistake() {
        FormulaException e = (FormulaException) org.assertj.core.api.Assertions.catchThrowable(() -> compile("[Age] + [Nope]"));
        assertThat(e.position()).isEqualTo(9);
    }
}
