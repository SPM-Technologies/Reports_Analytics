package com.hospital.patientreg.registration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospital.patientreg.registration.dao.PatientDao;
import com.hospital.patientreg.registration.model.CustomFieldDef;
import com.hospital.patientreg.registration.model.Department;
import com.hospital.patientreg.registration.model.PatientForm;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import com.hospital.patientreg.shared.tenant.HospitalAccess;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

class PatientValidatorTest {

    /** Validator with a fixed department list (of hospital 1 only) instead of a database. */
    private final PatientValidator validator = new PatientValidator(new PatientDao(null) {
        @Override
        public List<Department> findActiveDepartments(int hospitalId) {
            return hospitalId != 1 ? List.of()
                    : List.of(new Department(1, "GEN", "General Medicine"), new Department(2, "CAR", "Cardiology"));
        }
    }, new RegistrationSettings(null) {
        @Override
        public java.util.Map<String, List<String>> lists(int hospitalId) {
            java.util.Map<String, List<String>> lists = new java.util.LinkedHashMap<>(com.hospital.patientreg.registration.model.Lookups.ALL);
            lists.put("religions", List.of("Hindu", "Other", "Parsi"));   // this hospital's own list
            return lists;
        }

        @Override
        public List<CustomFieldDef> customFields(int hospitalId) {
            return CUSTOM;
        }
    });

    /** The hospital's own fields used by the custom-field tests. */
    private static final List<CustomFieldDef> CUSTOM = List.of(
            new CustomFieldDef(1, "abha_number", "ABHA number", CustomFieldDef.Type.TEXT, "personal", 0, true,
                    null, null, 14, List.of(), null, true, "MASKED", true, 1),
            new CustomFieldDef(1, "visits_last_year", "Visits last year", CustomFieldDef.Type.NUMBER, "visit", 0, false,
                    BigDecimal.ZERO, BigDecimal.valueOf(50), null, List.of(), null, true, "NORMAL", true, 1),
            new CustomFieldDef(1, "diet", "Diet", CustomFieldDef.Type.DROPDOWN, "medical", 0, false,
                    null, null, null, List.of("Veg", "Non-veg"), null, true, "NORMAL", true, 1),
            new CustomFieldDef(1, "symptoms", "Symptoms", CustomFieldDef.Type.MULTI_SELECT, "visit", 0, false,
                    null, null, null, List.of("Fever", "Cough", "Pain"), null, true, "NORMAL", true, 1),
            new CustomFieldDef(1, "follow_up_on", "Follow up on", CustomFieldDef.Type.DATE, "visit", 0, false,
                    null, null, null, List.of(), null, false, "NORMAL", true, 1),
            new CustomFieldDef(1, "old_field", "Old", CustomFieldDef.Type.TEXT, "visit", 0, true,
                    null, null, null, List.of(), null, false, "NORMAL", false, 1));

    @Test
    void customFieldsAreValidatedAndNormalised() {
        PatientForm f = validForm();
        f.getCustomValues().put("abha_number", " 12345678901234 ");
        f.getCustomValues().put("visits_last_year", "7");
        f.getCustomValues().put("diet", "Veg");
        f.getCustomValues().put("symptoms", "Cough,Fever,Cough");
        f.getCustomValues().put("follow_up_on", "2026-11-01");
        f.getCustomValues().put("not_defined", "x");          // unknown keys are dropped
        assertThat(validate(f).getErrorCount()).isZero();
        assertThat(f.getCustomValues()).containsExactly(
                java.util.Map.entry("abha_number", "12345678901234"), java.util.Map.entry("visits_last_year", "7"),
                java.util.Map.entry("diet", "Veg"), java.util.Map.entry("symptoms", "Cough; Fever"),
                java.util.Map.entry("follow_up_on", "2026-11-01"));
        assertThat(f.getCustomValuesJson()).isEqualTo(
                "{\"abha_number\":\"12345678901234\",\"visits_last_year\":\"7\",\"diet\":\"Veg\",\"symptoms\":\"Cough; Fever\",\"follow_up_on\":\"2026-11-01\"}");
    }

    @Test
    void wrongCustomValuesAreRejected() {
        PatientForm f = validForm();
        f.getCustomValues().remove("abha_number");   // required own field missing; the inactive required field is not asked for
        f.getCustomValues().put("visits_last_year", "51");
        f.getCustomValues().put("diet", "Vegan");
        f.getCustomValues().put("symptoms", "Fever,Headache");
        f.getCustomValues().put("follow_up_on", "tomorrow");
        Errors e = validate(f);
        assertThat(e.getFieldError("customValues[abha_number]").getDefaultMessage()).contains("required");
        assertThat(e.getFieldError("customValues[visits_last_year]").getDefaultMessage()).contains("between 0 and 50");
        assertThat(e.getFieldError("customValues[diet]")).isNotNull();
        assertThat(e.getFieldError("customValues[symptoms]")).isNotNull();
        assertThat(e.getFieldError("customValues[follow_up_on]")).isNotNull();
        assertThat(e.getFieldError("customValues[old_field]")).isNull();
        f.getCustomValues().put("abha_number", "1".repeat(15));
        f.getCustomValues().put("visits_last_year", "2.5");
        assertThat(validate(f).getFieldError("customValues[visits_last_year]").getDefaultMessage()).contains("whole number");
    }

    @Test
    void dropdownValuesComeFromTheHospitalsOwnList() {
        PatientForm f = validForm();
        f.getCustomValues().put("abha_number", "1");
        f.setReligion("Parsi");
        assertThat(validate(f).getFieldError("religion")).isNull();
        f.setReligion("Muslim");                                // a built-in value this hospital removed
        assertThat(validate(f).getFieldError("religion")).isNotNull();
    }

    /** The validator checks departments of the signed-in user's active hospital. */
    @BeforeEach
    void signIn() {
        TenantContext.set(new Tenant(1, "reg.test", "Registration Test", false,
                List.of(new HospitalAccess(1, "H001", "Main Hospital", "REGISTRATION", Set.of(Permission.REGISTER))),
                1, "127.0.0.1"));
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    static PatientForm validForm() {
        PatientForm f = new PatientForm();
        f.setRegistrationDate(LocalDate.now());
        f.setPatientCategory("GENERAL");
        f.setTitle("Mr.");
        f.setFirstName("Ravi");
        f.setLastName("Sharma");
        f.setGender("Male");
        f.setDateOfBirth(LocalDate.now().minusYears(40));
        f.setMobile("9876543210");
        f.setAddressLine1("12 MG Road");
        f.setCity("Pune");
        f.setState("Maharashtra");
        f.setPincode("411001");
        f.setCountry("India");
        f.setEmergencyName("Sita Sharma");
        f.setEmergencyRelationship("Spouse");
        f.setEmergencyPhone("9876500000");
        f.setDeptId(2);
        f.setConsultingDoctor("Dr. Mehta");
        f.setVisitType("NEW");
        f.setPriority("NORMAL");
        f.setChiefComplaint("Chest pain");
        f.setPaymentMode("UPI");
        f.setRegistrationFee(new BigDecimal("300"));
        f.getCustomValues().put("abha_number", "12345678901234");   // the hospital's required own field
        return f;
    }

    private Errors validate(PatientForm f) {
        Errors errors = new BeanPropertyBindingResult(f, "patient");
        validator.validate(f, errors);
        return errors;
    }

    @Test
    void validFormHasNoErrors() {
        assertThat(validate(validForm()).getAllErrors()).isEmpty();
    }

    @Test
    void requiredFieldsAreReported() {
        PatientForm f = new PatientForm();
        Errors e = validate(f);
        assertThat(e.getFieldErrors()).extracting("field")
                .contains("firstName", "lastName", "gender", "ageYears", "mobile", "addressLine1", "city", "state",
                        "pincode", "emergencyName", "emergencyRelationship", "emergencyPhone", "deptId",
                        "consultingDoctor", "chiefComplaint", "paymentMode");
    }

    @Test
    void dropdownValuesMustComeFromLookups() {
        PatientForm f = validForm();
        f.setGender("<svg/x='");
        f.setVisitType("WALKIN");
        Errors e = validate(f);
        assertThat(e.getFieldError("gender")).isNotNull();
        assertThat(e.getFieldError("visitType")).isNotNull();
    }

    @Test
    void textLongerThanColumnIsRejected() {
        PatientForm f = validForm();
        f.setOccupation("a".repeat(61));
        assertThat(validate(f).getFieldError("occupation").getDefaultMessage()).isEqualTo("Maximum 60 characters");
    }

    @Test
    void formatsAndRangesAreChecked() {
        PatientForm f = validForm();
        f.setMobile("12345");
        f.setPincode("ABC");
        f.setRegistrationFee(new BigDecimal("99999999"));
        f.setDeptId(999);
        Errors e = validate(f);
        assertThat(e.getFieldErrors()).extracting("field").contains("mobile", "pincode", "registrationFee", "deptId");
    }

    @Test
    void datesAreChecked() {
        PatientForm f = validForm();
        f.setRegistrationDate(LocalDate.now().plusDays(1));
        f.setDateOfBirth(LocalDate.now().minusYears(200));
        Errors e = validate(f);
        assertThat(e.getFieldError("registrationDate")).isNotNull();
        assertThat(e.getFieldError("dateOfBirth")).isNotNull();
    }

    @Test
    void insuranceFieldsRequiredOnlyWhenInsured() {
        PatientForm f = validForm();
        f.setHasInsurance(true);
        Errors e = validate(f);
        assertThat(e.getFieldErrors()).extracting("field").contains("insuranceProvider", "policyNumber", "policyValidTo");
        f.setHasInsurance(false);
        assertThat(validate(f).getAllErrors()).isEmpty();
    }
}
