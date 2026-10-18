package com.hospital.patientreg.registration.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Form-backing bean holding every registration field (70 fields).
 * The DAO splits it across patient, patient_contact, patient_emergency_contact,
 * patient_insurance, patient_medical_info and patient_visit.
 */
public class PatientForm {

    // ---- patient ----
    private Long patientId;
    private Integer hospitalId;            // set by the server from the signed-in user, never bound from the form
    /** values of the hospital's custom fields (stage 5b), key -> text; validated against the definitions */
    private java.util.Map<String, String> customValues = new java.util.LinkedHashMap<>();
    private String registrationNo;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate registrationDate;
    private String title;
    private String firstName;
    private String middleName;
    private String lastName;
    private String gender;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateOfBirth;
    private Integer ageYears;
    private String bloodGroup;
    private String maritalStatus;
    private String nationality = "Indian";
    private String religion;
    private String occupation;
    private String nationalId;
    private String preferredLanguage;
    private String patientCategory = "GENERAL";
    private String status = "ACTIVE";

    // ---- patient_contact ----
    private String mobile;
    private String alternatePhone;
    private String email;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String district;
    private String state;
    private String pincode;
    private String country = "India";
    private boolean permSameAsCurrent = true;
    private String permanentAddress;

    // ---- patient_emergency_contact ----
    private String emergencyName;
    private String emergencyRelationship;
    private String emergencyPhone;
    private String emergencyAltPhone;
    private String emergencyAddress;

    // ---- patient_insurance ----
    private boolean hasInsurance;
    private String insuranceProvider;
    private String policyNumber;
    private String groupNumber;
    private String policyHolderName;
    private String holderRelationship;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate policyValidFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate policyValidTo;
    private String coverageType;
    private BigDecimal sumInsured;

    // ---- patient_medical_info ----
    private BigDecimal heightCm;
    private BigDecimal weightKg;
    private BigDecimal bmi;
    private String knownAllergies;
    private String chronicConditions;
    private String currentMedications;
    private String pastSurgeries;
    private String familyHistory;
    private String smokingStatus;
    private String alcoholUse;
    private String disability;
    private String vaccinationStatus;

    // ---- patient_visit ----
    private Integer deptId;
    private String deptName;
    private String consultingDoctor;
    private String visitType = "NEW";
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate visitDate;
    private String referredBy;
    private String referralSource;
    private String chiefComplaint;
    private String symptomDuration;
    private String priority = "NORMAL";
    private String paymentMode;
    private BigDecimal registrationFee;
    private String remarks;

    /** Full display name, e.g. "Mr. Ravi K Sharma". */
    public String getFullName() {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {title, firstName, middleName, lastName}) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(part.trim());
            }
        }
        return sb.toString();
    }

    public Long getPatientId() { return patientId; }
    public java.util.Map<String, String> getCustomValues() { return customValues; }

    /** The custom values as a JSON object for patient.custom_fields (keys are validated identifiers). */
    public String getCustomValuesJson() {
        StringBuilder sb = new StringBuilder("{");
        if (customValues != null) {
            customValues.forEach((k, v) -> {
                if (v != null) {
                    sb.append(sb.length() > 1 ? "," : "").append(json(k)).append(':').append(json(v));
                }
            });
        }
        return sb.append('}').toString();
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
    public void setCustomValues(java.util.Map<String, String> customValues) { this.customValues = customValues; }
    public Integer getHospitalId() { return hospitalId; }
    public void setHospitalId(Integer hospitalId) { this.hospitalId = hospitalId; }
    public void setPatientId(Long patientId) { this.patientId = patientId; }
    public String getRegistrationNo() { return registrationNo; }
    public void setRegistrationNo(String registrationNo) { this.registrationNo = registrationNo; }
    public LocalDate getRegistrationDate() { return registrationDate; }
    public void setRegistrationDate(LocalDate registrationDate) { this.registrationDate = registrationDate; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getMiddleName() { return middleName; }
    public void setMiddleName(String middleName) { this.middleName = middleName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public Integer getAgeYears() { return ageYears; }
    public void setAgeYears(Integer ageYears) { this.ageYears = ageYears; }
    public String getBloodGroup() { return bloodGroup; }
    public void setBloodGroup(String bloodGroup) { this.bloodGroup = bloodGroup; }
    public String getMaritalStatus() { return maritalStatus; }
    public void setMaritalStatus(String maritalStatus) { this.maritalStatus = maritalStatus; }
    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }
    public String getReligion() { return religion; }
    public void setReligion(String religion) { this.religion = religion; }
    public String getOccupation() { return occupation; }
    public void setOccupation(String occupation) { this.occupation = occupation; }
    public String getNationalId() { return nationalId; }
    public void setNationalId(String nationalId) { this.nationalId = nationalId; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String preferredLanguage) { this.preferredLanguage = preferredLanguage; }
    public String getPatientCategory() { return patientCategory; }
    public void setPatientCategory(String patientCategory) { this.patientCategory = patientCategory; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getAlternatePhone() { return alternatePhone; }
    public void setAlternatePhone(String alternatePhone) { this.alternatePhone = alternatePhone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String addressLine1) { this.addressLine1 = addressLine1; }
    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String addressLine2) { this.addressLine2 = addressLine2; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public boolean isPermSameAsCurrent() { return permSameAsCurrent; }
    public void setPermSameAsCurrent(boolean permSameAsCurrent) { this.permSameAsCurrent = permSameAsCurrent; }
    public String getPermanentAddress() { return permanentAddress; }
    public void setPermanentAddress(String permanentAddress) { this.permanentAddress = permanentAddress; }

    public String getEmergencyName() { return emergencyName; }
    public void setEmergencyName(String emergencyName) { this.emergencyName = emergencyName; }
    public String getEmergencyRelationship() { return emergencyRelationship; }
    public void setEmergencyRelationship(String emergencyRelationship) { this.emergencyRelationship = emergencyRelationship; }
    public String getEmergencyPhone() { return emergencyPhone; }
    public void setEmergencyPhone(String emergencyPhone) { this.emergencyPhone = emergencyPhone; }
    public String getEmergencyAltPhone() { return emergencyAltPhone; }
    public void setEmergencyAltPhone(String emergencyAltPhone) { this.emergencyAltPhone = emergencyAltPhone; }
    public String getEmergencyAddress() { return emergencyAddress; }
    public void setEmergencyAddress(String emergencyAddress) { this.emergencyAddress = emergencyAddress; }

    public boolean isHasInsurance() { return hasInsurance; }
    public void setHasInsurance(boolean hasInsurance) { this.hasInsurance = hasInsurance; }
    public String getInsuranceProvider() { return insuranceProvider; }
    public void setInsuranceProvider(String insuranceProvider) { this.insuranceProvider = insuranceProvider; }
    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
    public String getGroupNumber() { return groupNumber; }
    public void setGroupNumber(String groupNumber) { this.groupNumber = groupNumber; }
    public String getPolicyHolderName() { return policyHolderName; }
    public void setPolicyHolderName(String policyHolderName) { this.policyHolderName = policyHolderName; }
    public String getHolderRelationship() { return holderRelationship; }
    public void setHolderRelationship(String holderRelationship) { this.holderRelationship = holderRelationship; }
    public LocalDate getPolicyValidFrom() { return policyValidFrom; }
    public void setPolicyValidFrom(LocalDate policyValidFrom) { this.policyValidFrom = policyValidFrom; }
    public LocalDate getPolicyValidTo() { return policyValidTo; }
    public void setPolicyValidTo(LocalDate policyValidTo) { this.policyValidTo = policyValidTo; }
    public String getCoverageType() { return coverageType; }
    public void setCoverageType(String coverageType) { this.coverageType = coverageType; }
    public BigDecimal getSumInsured() { return sumInsured; }
    public void setSumInsured(BigDecimal sumInsured) { this.sumInsured = sumInsured; }

    public BigDecimal getHeightCm() { return heightCm; }
    public void setHeightCm(BigDecimal heightCm) { this.heightCm = heightCm; }
    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }
    public BigDecimal getBmi() { return bmi; }
    public void setBmi(BigDecimal bmi) { this.bmi = bmi; }
    public String getKnownAllergies() { return knownAllergies; }
    public void setKnownAllergies(String knownAllergies) { this.knownAllergies = knownAllergies; }
    public String getChronicConditions() { return chronicConditions; }
    public void setChronicConditions(String chronicConditions) { this.chronicConditions = chronicConditions; }
    public String getCurrentMedications() { return currentMedications; }
    public void setCurrentMedications(String currentMedications) { this.currentMedications = currentMedications; }
    public String getPastSurgeries() { return pastSurgeries; }
    public void setPastSurgeries(String pastSurgeries) { this.pastSurgeries = pastSurgeries; }
    public String getFamilyHistory() { return familyHistory; }
    public void setFamilyHistory(String familyHistory) { this.familyHistory = familyHistory; }
    public String getSmokingStatus() { return smokingStatus; }
    public void setSmokingStatus(String smokingStatus) { this.smokingStatus = smokingStatus; }
    public String getAlcoholUse() { return alcoholUse; }
    public void setAlcoholUse(String alcoholUse) { this.alcoholUse = alcoholUse; }
    public String getDisability() { return disability; }
    public void setDisability(String disability) { this.disability = disability; }
    public String getVaccinationStatus() { return vaccinationStatus; }
    public void setVaccinationStatus(String vaccinationStatus) { this.vaccinationStatus = vaccinationStatus; }

    public Integer getDeptId() { return deptId; }
    public void setDeptId(Integer deptId) { this.deptId = deptId; }
    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }
    public String getConsultingDoctor() { return consultingDoctor; }
    public void setConsultingDoctor(String consultingDoctor) { this.consultingDoctor = consultingDoctor; }
    public String getVisitType() { return visitType; }
    public void setVisitType(String visitType) { this.visitType = visitType; }
    public LocalDate getVisitDate() { return visitDate; }
    public void setVisitDate(LocalDate visitDate) { this.visitDate = visitDate; }
    public String getReferredBy() { return referredBy; }
    public void setReferredBy(String referredBy) { this.referredBy = referredBy; }
    public String getReferralSource() { return referralSource; }
    public void setReferralSource(String referralSource) { this.referralSource = referralSource; }
    public String getChiefComplaint() { return chiefComplaint; }
    public void setChiefComplaint(String chiefComplaint) { this.chiefComplaint = chiefComplaint; }
    public String getSymptomDuration() { return symptomDuration; }
    public void setSymptomDuration(String symptomDuration) { this.symptomDuration = symptomDuration; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String paymentMode) { this.paymentMode = paymentMode; }
    public BigDecimal getRegistrationFee() { return registrationFee; }
    public void setRegistrationFee(BigDecimal registrationFee) { this.registrationFee = registrationFee; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
}
