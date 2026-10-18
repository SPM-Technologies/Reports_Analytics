package com.hospital.patientreg.registration.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in dropdown values (a hospital may replace the customisable ones), used by the form and report filters. */
public final class Lookups {

    public static final Map<String, List<String>> ALL = new LinkedHashMap<>();

    static {
        ALL.put("titles", List.of("Mr.", "Mrs.", "Ms.", "Master", "Baby", "Dr."));
        ALL.put("genders", List.of("Male", "Female", "Other"));
        ALL.put("bloodGroups", List.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"));
        ALL.put("maritalStatuses", List.of("Single", "Married", "Divorced", "Widowed"));
        ALL.put("religions", List.of("Hindu", "Muslim", "Christian", "Sikh", "Buddhist", "Jain", "Other"));
        ALL.put("languages", List.of("English", "Hindi", "Marathi", "Gujarati", "Tamil", "Telugu", "Kannada",
                "Malayalam", "Bengali", "Punjabi", "Other"));
        ALL.put("categories", List.of("GENERAL", "SENIOR_CITIZEN", "STAFF", "VIP", "BPL"));
        ALL.put("states", List.of("Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Delhi",
                "Goa", "Gujarat", "Haryana", "Himachal Pradesh", "Jammu and Kashmir", "Jharkhand", "Karnataka",
                "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram", "Nagaland", "Odisha",
                "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura", "Uttar Pradesh",
                "Uttarakhand", "West Bengal"));
        ALL.put("relationships", List.of("Father", "Mother", "Spouse", "Son", "Daughter", "Brother", "Sister",
                "Guardian", "Friend", "Other"));
        ALL.put("holderRelationships", List.of("Self", "Spouse", "Parent", "Child", "Employer"));
        ALL.put("coverageTypes", List.of("Individual", "Family Floater", "Corporate", "Government Scheme"));
        ALL.put("smokingStatuses", List.of("Never", "Former", "Current"));
        ALL.put("alcoholUses", List.of("Never", "Occasional", "Regular"));
        ALL.put("vaccinationStatuses", List.of("Fully Vaccinated", "Partially Vaccinated", "Not Vaccinated",
                "Unknown"));
        ALL.put("visitTypes", List.of("NEW", "FOLLOW_UP", "EMERGENCY"));
        ALL.put("referralSources", List.of("Self", "Doctor", "Hospital", "Health Camp", "Online", "Other"));
        ALL.put("priorities", List.of("NORMAL", "URGENT", "CRITICAL"));
        ALL.put("paymentModes", List.of("CASH", "CARD", "UPI", "INSURANCE", "CREDIT"));
    }

    /**
     * Lists a hospital may change (Settings > Lists): label and the size of the column the value goes into.
     * The other lists (genders, categories, visit types, priorities, payment modes) carry meaning in reports
     * and code, so they stay fixed.
     */
    public record Customisable(String label, int maxLength) {
    }

    public static final Map<String, Customisable> CUSTOMISABLE = new LinkedHashMap<>();

    static {
        CUSTOMISABLE.put("titles", new Customisable("Titles", 10));
        CUSTOMISABLE.put("bloodGroups", new Customisable("Blood groups", 5));
        CUSTOMISABLE.put("maritalStatuses", new Customisable("Marital statuses", 15));
        CUSTOMISABLE.put("religions", new Customisable("Religions", 30));
        CUSTOMISABLE.put("languages", new Customisable("Preferred languages", 30));
        CUSTOMISABLE.put("states", new Customisable("States", 60));
        CUSTOMISABLE.put("relationships", new Customisable("Emergency contact relationships", 30));
        CUSTOMISABLE.put("holderRelationships", new Customisable("Relationships to policy holder", 30));
        CUSTOMISABLE.put("coverageTypes", new Customisable("Insurance coverage types", 30));
        CUSTOMISABLE.put("smokingStatuses", new Customisable("Smoking statuses", 20));
        CUSTOMISABLE.put("alcoholUses", new Customisable("Alcohol use", 20));
        CUSTOMISABLE.put("vaccinationStatuses", new Customisable("Vaccination statuses", 30));
        CUSTOMISABLE.put("referralSources", new Customisable("Referral sources", 30));
    }

    /**
     * Optional standard fields a hospital may hide from its form (Settings > Form fields), with their labels.
     * Required fields cannot be hidden.
     */
    public static final Map<String, String> HIDEABLE = new LinkedHashMap<>();

    static {
        HIDEABLE.put("middleName", "Middle name");
        HIDEABLE.put("bloodGroup", "Blood group");
        HIDEABLE.put("maritalStatus", "Marital status");
        HIDEABLE.put("nationality", "Nationality");
        HIDEABLE.put("religion", "Religion");
        HIDEABLE.put("occupation", "Occupation");
        HIDEABLE.put("nationalId", "Aadhaar number");
        HIDEABLE.put("preferredLanguage", "Preferred language");
        HIDEABLE.put("alternatePhone", "Alternate phone");
        HIDEABLE.put("email", "Email");
        HIDEABLE.put("addressLine2", "Address line 2");
        HIDEABLE.put("district", "District");
        HIDEABLE.put("emergencyAltPhone", "Emergency alternate phone");
        HIDEABLE.put("emergencyAddress", "Emergency contact address");
        HIDEABLE.put("groupNumber", "Insurance group number");
        HIDEABLE.put("policyHolderName", "Policy holder name");
        HIDEABLE.put("heightCm", "Height");
        HIDEABLE.put("weightKg", "Weight");
        HIDEABLE.put("vaccinationStatus", "Vaccination status");
        HIDEABLE.put("smokingStatus", "Smoking");
        HIDEABLE.put("alcoholUse", "Alcohol");
        HIDEABLE.put("disability", "Disability");
        HIDEABLE.put("knownAllergies", "Known allergies");
        HIDEABLE.put("chronicConditions", "Chronic conditions");
        HIDEABLE.put("currentMedications", "Current medications");
        HIDEABLE.put("pastSurgeries", "Past surgeries");
        HIDEABLE.put("familyHistory", "Family history");
        HIDEABLE.put("referralSource", "Referral source");
        HIDEABLE.put("referredBy", "Referred by");
        HIDEABLE.put("symptomDuration", "Symptom duration");
        HIDEABLE.put("remarks", "Remarks");
    }

    private Lookups() {
    }
}
