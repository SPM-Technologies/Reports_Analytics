package com.hospital.patientreg.registration.service;

import com.hospital.patientreg.registration.dao.PatientDao;
import com.hospital.patientreg.registration.model.Department;
import com.hospital.patientreg.registration.model.PatientForm;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import com.hospital.patientreg.shared.tenant.HospitalAccess;
import com.hospital.patientreg.shared.tenant.HospitalCreatedEvent;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import com.hospital.patientreg.shared.tenant.TenantContext;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientService {

    private final PatientDao patientDao;
    private final RegistrationSettings settings;

    public PatientService(PatientDao patientDao, RegistrationSettings settings) {
        this.patientDao = patientDao;
        this.settings = settings;
    }

    /**
     * Saves the registration into all tables of the user's active hospital in one transaction and returns the
     * new patient id. The hospital always comes from the signed-in user, never from the form.
     */
    @Transactional
    public long register(PatientForm form) {
        HospitalAccess hospital = TenantContext.require().requireHere(Permission.REGISTER);
        // fields the hospital hid from its form are never stored, whatever the request contained
        BeanWrapper bean = new BeanWrapperImpl(form);
        settings.hiddenFields(hospital.hospitalId()).forEach(field -> bean.setPropertyValue(field, null));
        prepare(form);
        patientDao.insert(form, hospital.hospitalId(), hospital.code());
        return form.getPatientId();
    }

    /**
     * The patient's full record, or null if it does not exist or the user may not open it. Row-level security
     * already hides other hospitals' patients; the permission is checked for the patient's own hospital.
     */
    @Transactional(readOnly = true)
    public PatientForm find(long patientId) {
        PatientForm p = patientDao.findById(patientId);
        Tenant t = TenantContext.require();
        if (p == null || p.getHospitalId() == null || !t.can(Permission.VIEW_PATIENT, p.getHospitalId())) {
            return null;
        }
        p.setCustomValues(settings.customValues(patientId));
        return p;
    }

    /** Departments of the active hospital. */
    public List<Department> departments() {
        return patientDao.findActiveDepartments(TenantContext.require().activeHospitalId());
    }

    /** Dashboard figures of the active hospital. */
    public Map<String, Object> dashboardCounts() {
        return patientDao.dashboardCounts(TenantContext.require().activeHospitalId());
    }

    /** A new hospital gets the default department list (identity module event, same transaction). */
    @EventListener
    public void onHospitalCreated(HospitalCreatedEvent event) {
        patientDao.seedDepartments(event.hospitalId());
    }

    /** Fills derived values: dates, age from DOB, BMI, cleared insurance/permanent-address fields. */
    static void prepare(PatientForm f) {
        LocalDate today = LocalDate.now();
        if (f.getRegistrationDate() == null) f.setRegistrationDate(today);
        if (f.getVisitDate() == null) f.setVisitDate(f.getRegistrationDate());
        if (f.getDateOfBirth() != null) {
            f.setAgeYears(Period.between(f.getDateOfBirth(), f.getRegistrationDate()).getYears());
        }
        if (f.getHeightCm() != null && f.getWeightKg() != null && f.getHeightCm().signum() > 0) {
            BigDecimal meters = f.getHeightCm().divide(BigDecimal.valueOf(100));
            f.setBmi(f.getWeightKg().divide(meters.multiply(meters), 1, RoundingMode.HALF_UP));
        } else {
            f.setBmi(null);
        }
        if (f.isPermSameAsCurrent()) {
            f.setPermanentAddress(null);
        }
        if (!f.isHasInsurance()) {
            f.setInsuranceProvider(null);
            f.setPolicyNumber(null);
            f.setGroupNumber(null);
            f.setPolicyHolderName(null);
            f.setHolderRelationship(null);
            f.setPolicyValidFrom(null);
            f.setPolicyValidTo(null);
            f.setCoverageType(null);
            f.setSumInsured(null);
        }
        if (f.getRegistrationFee() == null) f.setRegistrationFee(BigDecimal.ZERO);
    }
}
