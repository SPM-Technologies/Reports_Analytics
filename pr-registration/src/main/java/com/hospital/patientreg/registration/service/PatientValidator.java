package com.hospital.patientreg.registration.service;

import com.hospital.patientreg.registration.dao.PatientDao;
import com.hospital.patientreg.registration.model.Department;
import com.hospital.patientreg.registration.model.CustomFieldDef;
import com.hospital.patientreg.registration.model.PatientForm;
import com.hospital.patientreg.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.validation.Validator;

/**
 * Server-side validation for the registration form. The browser checks are only a convenience:
 * everything that reaches the database (allowed values, lengths, numeric ranges) is checked here.
 */
@Component
public class PatientValidator implements Validator {

    private static final Pattern PHONE = Pattern.compile("\\d{10}");
    private static final Pattern PINCODE = Pattern.compile("\\d{6}");
    private static final Pattern AADHAAR = Pattern.compile("\\d{12}");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");

    private static final BigDecimal MAX_FEE = new BigDecimal("100000");
    private static final BigDecimal MAX_SUM_INSURED = new BigDecimal("100000000");
    private static final int MAX_AGE = 130;

    /** Dropdown-backed fields and the only values they may hold. */
    private static final Map<String, String> SELECT_FIELDS = new LinkedHashMap<>();
    /** Free-text fields and their column sizes in schema.sql. */
    private static final Map<String, Integer> MAX_LENGTHS = new LinkedHashMap<>();

    static {
        SELECT_FIELDS.put("title", "titles");
        SELECT_FIELDS.put("gender", "genders");
        SELECT_FIELDS.put("bloodGroup", "bloodGroups");
        SELECT_FIELDS.put("maritalStatus", "maritalStatuses");
        SELECT_FIELDS.put("religion", "religions");
        SELECT_FIELDS.put("preferredLanguage", "languages");
        SELECT_FIELDS.put("patientCategory", "categories");
        SELECT_FIELDS.put("state", "states");
        SELECT_FIELDS.put("emergencyRelationship", "relationships");
        SELECT_FIELDS.put("holderRelationship", "holderRelationships");
        SELECT_FIELDS.put("coverageType", "coverageTypes");
        SELECT_FIELDS.put("smokingStatus", "smokingStatuses");
        SELECT_FIELDS.put("alcoholUse", "alcoholUses");
        SELECT_FIELDS.put("vaccinationStatus", "vaccinationStatuses");
        SELECT_FIELDS.put("visitType", "visitTypes");
        SELECT_FIELDS.put("referralSource", "referralSources");
        SELECT_FIELDS.put("priority", "priorities");
        SELECT_FIELDS.put("paymentMode", "paymentModes");

        MAX_LENGTHS.put("firstName", 60);
        MAX_LENGTHS.put("middleName", 60);
        MAX_LENGTHS.put("lastName", 60);
        MAX_LENGTHS.put("nationality", 40);
        MAX_LENGTHS.put("occupation", 60);
        MAX_LENGTHS.put("email", 100);
        MAX_LENGTHS.put("addressLine1", 150);
        MAX_LENGTHS.put("addressLine2", 150);
        MAX_LENGTHS.put("city", 60);
        MAX_LENGTHS.put("district", 60);
        MAX_LENGTHS.put("country", 60);
        MAX_LENGTHS.put("permanentAddress", 300);
        MAX_LENGTHS.put("emergencyName", 100);
        MAX_LENGTHS.put("emergencyAddress", 300);
        MAX_LENGTHS.put("insuranceProvider", 100);
        MAX_LENGTHS.put("policyNumber", 40);
        MAX_LENGTHS.put("groupNumber", 40);
        MAX_LENGTHS.put("policyHolderName", 100);
        MAX_LENGTHS.put("knownAllergies", 300);
        MAX_LENGTHS.put("chronicConditions", 300);
        MAX_LENGTHS.put("currentMedications", 300);
        MAX_LENGTHS.put("pastSurgeries", 300);
        MAX_LENGTHS.put("familyHistory", 300);
        MAX_LENGTHS.put("disability", 100);
        MAX_LENGTHS.put("consultingDoctor", 100);
        MAX_LENGTHS.put("referredBy", 100);
        MAX_LENGTHS.put("chiefComplaint", 300);
        MAX_LENGTHS.put("symptomDuration", 30);
        MAX_LENGTHS.put("remarks", 300);
    }

    private final PatientDao patientDao;
    private final RegistrationSettings settings;

    public PatientValidator(PatientDao patientDao, RegistrationSettings settings) {
        this.settings = settings;
        this.patientDao = patientDao;
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return PatientForm.class.equals(clazz);
    }

    @Override
    public void validate(Object target, Errors e) {
        PatientForm f = (PatientForm) target;
        int hospital = TenantContext.require().activeHospitalId();
        Map<String, List<String>> lists = settings.lists(hospital);
        LocalDate today = LocalDate.now();
        LocalDate regDate = f.getRegistrationDate() != null ? f.getRegistrationDate() : today;

        // personal
        required(e, "firstName", "First name");
        required(e, "lastName", "Last name");
        required(e, "gender", "Gender");
        if (f.getRegistrationDate() != null && f.getRegistrationDate().isAfter(today)) {
            e.rejectValue("registrationDate", "future", "Registration date cannot be in the future");
        }
        if (f.getDateOfBirth() != null) {
            if (f.getDateOfBirth().isAfter(regDate)) {
                e.rejectValue("dateOfBirth", "future", "Date of birth cannot be after the registration date");
            } else if (f.getDateOfBirth().isBefore(regDate.minusYears(MAX_AGE))) {
                e.rejectValue("dateOfBirth", "range", "Date of birth is more than " + MAX_AGE + " years ago");
            }
        } else if (f.getAgeYears() == null) {
            e.rejectValue("ageYears", "required", "Enter date of birth or age");
        }
        if (f.getAgeYears() != null && (f.getAgeYears() < 0 || f.getAgeYears() > MAX_AGE)) {
            e.rejectValue("ageYears", "range", "Age must be between 0 and " + MAX_AGE);
        }
        pattern(e, "nationalId", f.getNationalId(), AADHAAR, "Aadhaar number must be 12 digits");
        required(e, "patientCategory", "Patient category");

        // contact
        required(e, "mobile", "Mobile");
        pattern(e, "mobile", f.getMobile(), PHONE, "Mobile must be 10 digits");
        pattern(e, "alternatePhone", f.getAlternatePhone(), PHONE, "Phone must be 10 digits");
        pattern(e, "email", f.getEmail(), EMAIL, "Invalid email address");
        required(e, "addressLine1", "Address");
        required(e, "city", "City");
        required(e, "state", "State");
        required(e, "pincode", "Pincode");
        pattern(e, "pincode", f.getPincode(), PINCODE, "Pincode must be 6 digits");
        required(e, "country", "Country");
        if (!f.isPermSameAsCurrent()) {
            required(e, "permanentAddress", "Permanent address");
        }

        // emergency
        required(e, "emergencyName", "Emergency contact name");
        required(e, "emergencyRelationship", "Relationship");
        required(e, "emergencyPhone", "Emergency phone");
        pattern(e, "emergencyPhone", f.getEmergencyPhone(), PHONE, "Phone must be 10 digits");
        pattern(e, "emergencyAltPhone", f.getEmergencyAltPhone(), PHONE, "Phone must be 10 digits");

        // insurance
        if (f.isHasInsurance()) {
            required(e, "insuranceProvider", "Insurance provider");
            required(e, "policyNumber", "Policy number");
            required(e, "policyValidTo", "Valid to");
            if (f.getPolicyValidFrom() != null && f.getPolicyValidTo() != null
                    && f.getPolicyValidTo().isBefore(f.getPolicyValidFrom())) {
                e.rejectValue("policyValidTo", "range", "Valid-to date must be after valid-from date");
            }
            range(e, "sumInsured", f.getSumInsured(), MAX_SUM_INSURED);
        }

        // medical
        if (f.getHeightCm() != null && (f.getHeightCm().doubleValue() < 30 || f.getHeightCm().doubleValue() > 250)) {
            e.rejectValue("heightCm", "range", "Height must be 30-250 cm");
        }
        if (f.getWeightKg() != null && (f.getWeightKg().doubleValue() < 1 || f.getWeightKg().doubleValue() > 400)) {
            e.rejectValue("weightKg", "range", "Weight must be 1-400 kg");
        }

        // visit
        if (f.getDeptId() == null) {
            e.rejectValue("deptId", "required", "Department is required");
        } else if (patientDao.findActiveDepartments(TenantContext.require().activeHospitalId()).stream().map(Department::deptId)
                .noneMatch(id -> id.equals(f.getDeptId()))) {   // Integer objects: equals, never ==
            e.rejectValue("deptId", "invalid", "Select a valid department");
        }
        required(e, "consultingDoctor", "Consulting doctor");
        required(e, "visitType", "Visit type");
        required(e, "chiefComplaint", "Chief complaint");
        required(e, "priority", "Priority");
        required(e, "paymentMode", "Payment mode");
        if (f.getVisitDate() != null) {
            if (f.getVisitDate().isAfter(today)) {
                e.rejectValue("visitDate", "future", "Visit date cannot be in the future");
            } else if (f.getVisitDate().isBefore(regDate)) {
                e.rejectValue("visitDate", "range", "Visit date cannot be before the registration date");
            }
        }
        range(e, "registrationFee", f.getRegistrationFee(), MAX_FEE);

        // dropdown values must come from the fixed lists; text must fit its column
        SELECT_FIELDS.forEach((field, list) -> {
            Object value = e.getFieldValue(field);
            if (value instanceof String s && !s.isEmpty() && !e.hasFieldErrors(field)
                    && !lists.get(list).contains(s)) {
                e.rejectValue(field, "invalid", "Select a valid value");
            }
        });
        MAX_LENGTHS.forEach((field, max) -> {
            Object value = e.getFieldValue(field);
            if (value instanceof String s && s.length() > max && !e.hasFieldErrors(field)) {
                e.rejectValue(field, "length", "Maximum " + max + " characters");
            }
        });
        validateCustomFields(f, e, hospital);
    }

    /**
     * The hospital's custom fields (stage 5b): required, type, range, length and allowed values, exactly like
     * the standard fields. Values are normalised (dates ISO, several values joined with "; "); values of
     * unknown or inactive fields are dropped, so nothing undefined reaches the database.
     */
    private void validateCustomFields(PatientForm f, Errors e, int hospital) {
        Map<String, String> in = f.getCustomValues() == null ? Map.of() : f.getCustomValues();
        Map<String, String> clean = new LinkedHashMap<>();
        for (CustomFieldDef d : settings.customFields(hospital)) {
            if (!d.active()) {
                continue;
            }
            String path = "customValues[" + d.key() + "]";
            String raw = in.get(d.key()) == null ? "" : in.get(d.key()).trim();
            if (raw.isEmpty()) {
                if (d.required()) {
                    e.rejectValue(path, "required", d.label() + " is required");
                }
                continue;
            }
            String value = customValue(d, raw, path, e);
            if (value != null) {
                clean.put(d.key(), value);
            }
        }
        f.setCustomValues(clean);
    }

    /** The normalised value, or null after rejecting it. */
    private static String customValue(CustomFieldDef d, String raw, String path, Errors e) {
        switch (d.type()) {
            case TEXT, LONG_TEXT -> {
                if (raw.length() > d.effectiveMaxLength()) {
                    e.rejectValue(path, "length", "Maximum " + d.effectiveMaxLength() + " characters");
                    return null;
                }
                // control characters (a NUL is refused by PostgreSQL JSONB) other than line breaks and tabs
                if (raw.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\r' && c != '\t')) {
                    e.rejectValue(path, "invalid", "Remove special control characters");
                    return null;
                }
                return raw;
            }
            case NUMBER, DECIMAL -> {
                BigDecimal n;
                try {
                    n = new BigDecimal(raw);
                } catch (NumberFormatException ex) {
                    e.rejectValue(path, "number", "Enter a number");
                    return null;
                }
                if (d.type() == CustomFieldDef.Type.NUMBER && n.stripTrailingZeros().scale() > 0) {
                    e.rejectValue(path, "number", "Enter a whole number");
                    return null;
                }
                if (n.scale() > 4 || n.abs().compareTo(new BigDecimal("1000000000000")) > 0) {
                    e.rejectValue(path, "number", "Number too long");
                    return null;
                }
                if (d.minValue() != null && n.compareTo(d.minValue()) < 0 || d.maxValue() != null && n.compareTo(d.maxValue()) > 0) {
                    e.rejectValue(path, "range", "Must be between " + (d.minValue() == null ? "any" : d.minValue().toPlainString())
                            + " and " + (d.maxValue() == null ? "any" : d.maxValue().toPlainString()));
                    return null;
                }
                return n.stripTrailingZeros().toPlainString();
            }
            case DATE -> {
                try {
                    LocalDate date = LocalDate.parse(raw);
                    if (date.getYear() < 1900 || date.getYear() > 2299) {
                        e.rejectValue(path, "date", "Enter a date from 1900 to 2299");
                        return null;
                    }
                    return date.toString();
                } catch (DateTimeParseException ex) {
                    e.rejectValue(path, "date", "Enter a date");
                    return null;
                }
            }
            case YES_NO -> {
                if (!raw.equals("true") && !raw.equals("false")) {
                    e.rejectValue(path, "invalid", "Select Yes or No");
                    return null;
                }
                return raw;
            }
            case DROPDOWN -> {
                if (!d.options().contains(raw)) {
                    e.rejectValue(path, "invalid", "Select a valid value");
                    return null;
                }
                return raw;
            }
            default -> {   // MULTI_SELECT: the browser sends the ticked values comma-separated
                List<String> chosen = new ArrayList<>();
                for (String part : raw.split(",")) {
                    String v = part.trim();
                    if (v.isEmpty()) {
                        continue;
                    }
                    if (!d.options().contains(v)) {
                        e.rejectValue(path, "invalid", "Select valid values");
                        return null;
                    }
                    if (!chosen.contains(v)) {
                        chosen.add(v);
                    }
                }
                if (chosen.isEmpty() && d.required()) {
                    // e.g. a forged "," passes the empty check above but holds no value
                    e.rejectValue(path, "required", d.label() + " is required");
                }
                return chosen.isEmpty() ? null : String.join(CustomFieldDef.MULTI_SEPARATOR, chosen);
            }
        }
    }

    private static void required(Errors e, String field, String label) {
        if (!e.hasFieldErrors(field)) {
            ValidationUtils.rejectIfEmptyOrWhitespace(e, field, "required", label + " is required");
        }
    }

    private static void pattern(Errors e, String field, String value, Pattern p, String msg) {
        if (value != null && !value.isBlank() && !p.matcher(value.trim()).matches() && !e.hasFieldErrors(field)) {
            e.rejectValue(field, "format", msg);
        }
    }

    private static void range(Errors e, String field, BigDecimal value, BigDecimal max) {
        if (value == null || e.hasFieldErrors(field)) return;
        if (value.signum() < 0) {
            e.rejectValue(field, "range", "Cannot be negative");
        } else if (value.compareTo(max) > 0) {
            e.rejectValue(field, "range", "Cannot exceed " + max.toPlainString());
        } else if (value.scale() > 2) {
            e.rejectValue(field, "range", "At most 2 decimal places");
        }
    }
}
