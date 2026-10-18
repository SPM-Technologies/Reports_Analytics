package com.hospital.patientreg.registration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospital.patientreg.registration.model.PatientForm;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Derived values computed by PatientService before saving. */
class PatientServicePrepareTest {

    @Test
    void ageIsDerivedFromDateOfBirthAtRegistration() {
        PatientForm f = PatientValidatorTest.validForm();
        f.setRegistrationDate(LocalDate.of(2026, 10, 15));
        f.setDateOfBirth(LocalDate.of(1985, 10, 16));
        f.setAgeYears(99);
        PatientService.prepare(f);
        assertThat(f.getAgeYears()).isEqualTo(40);
    }

    @Test
    void bmiIsCalculatedAndVisitDateDefaults() {
        PatientForm f = PatientValidatorTest.validForm();
        f.setHeightCm(new BigDecimal("172"));
        f.setWeightKg(new BigDecimal("70"));
        f.setVisitDate(null);
        PatientService.prepare(f);
        assertThat(f.getBmi()).isEqualByComparingTo("23.7");
        assertThat(f.getVisitDate()).isEqualTo(f.getRegistrationDate());
    }

    @Test
    void insuranceAndPermanentAddressAreClearedWhenNotApplicable() {
        PatientForm f = PatientValidatorTest.validForm();
        f.setHasInsurance(false);
        f.setInsuranceProvider("Star Health");
        f.setPermSameAsCurrent(true);
        f.setPermanentAddress("Somewhere");
        PatientService.prepare(f);
        assertThat(f.getInsuranceProvider()).isNull();
        assertThat(f.getPermanentAddress()).isNull();
    }
}
