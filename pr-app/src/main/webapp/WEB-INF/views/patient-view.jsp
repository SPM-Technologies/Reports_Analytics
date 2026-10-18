<%@ include file="common/taglibs.jspf" %>
<c:set var="pageTitle" value="Patient ${p.registrationNo}"/><c:set var="activeNav" value=""/>
<%@ include file="common/header.jspf" %>

<c:if test="${not empty message}"><div class="alert alert-success">${message}</div></c:if>

<div class="view-head">
    <div>
        <h1><c:out value="${p.fullName}"/></h1>
        <div class="muted"><c:out value="${hospital.name}"/> &middot; Reg No <strong><c:out value="${p.registrationNo}"/></strong> &middot; Registered <c:out value="${p.registrationDate}"/>
            &middot; <c:out value="${p.gender}"/>, <c:out value="${p.ageYears}"/> yrs &middot; <c:out value="${p.patientCategory}"/></div>
    </div>
    <div class="no-print">
        <button type="button" class="btn" data-print>Print</button>
        <c:if test="${tenant.allowedHere('REGISTER')}"><a class="btn btn-primary" href="${ctx}/patient/new">New Registration</a></c:if>
    </div>
</div>

<section class="card">
    <h2>Personal Details</h2>
    <dl class="dl">
        <dt>Title</dt><dd><c:out value="${p.title}"/></dd>
        <dt>First Name</dt><dd><c:out value="${p.firstName}"/></dd>
        <dt>Middle Name</dt><dd><c:out value="${p.middleName}"/></dd>
        <dt>Last Name</dt><dd><c:out value="${p.lastName}"/></dd>
        <dt>Gender</dt><dd><c:out value="${p.gender}"/></dd>
        <dt>Date of Birth</dt><dd><c:out value="${p.dateOfBirth}"/></dd>
        <dt>Age</dt><dd><c:out value="${p.ageYears}"/></dd>
        <dt>Blood Group</dt><dd><c:out value="${p.bloodGroup}"/></dd>
        <dt>Marital Status</dt><dd><c:out value="${p.maritalStatus}"/></dd>
        <dt>Nationality</dt><dd><c:out value="${p.nationality}"/></dd>
        <dt>Religion</dt><dd><c:out value="${p.religion}"/></dd>
        <dt>Occupation</dt><dd><c:out value="${p.occupation}"/></dd>
        <dt>Aadhaar No.</dt><dd><c:out value="${p.nationalId}"/></dd>
        <dt>Preferred Language</dt><dd><c:out value="${p.preferredLanguage}"/></dd>
        <dt>Category</dt><dd><c:out value="${p.patientCategory}"/></dd>
        <dt>Status</dt><dd><c:out value="${p.status}"/></dd>
    </dl>
</section>

<section class="card">
    <h2>Contact &amp; Address</h2>
    <dl class="dl">
        <dt>Mobile</dt><dd><c:out value="${p.mobile}"/></dd>
        <dt>Alternate Phone</dt><dd><c:out value="${p.alternatePhone}"/></dd>
        <dt>Email</dt><dd><c:out value="${p.email}"/></dd>
        <dt>Address</dt><dd><c:out value="${p.addressLine1}"/><c:if test="${not empty p.addressLine2}">, <c:out value="${p.addressLine2}"/></c:if></dd>
        <dt>City</dt><dd><c:out value="${p.city}"/></dd>
        <dt>District</dt><dd><c:out value="${p.district}"/></dd>
        <dt>State</dt><dd><c:out value="${p.state}"/></dd>
        <dt>Pincode</dt><dd><c:out value="${p.pincode}"/></dd>
        <dt>Country</dt><dd><c:out value="${p.country}"/></dd>
        <dt>Permanent Address</dt><dd><c:out value="${p.permSameAsCurrent ? 'Same as current' : p.permanentAddress}"/></dd>
    </dl>
</section>

<section class="card">
    <h2>Emergency Contact</h2>
    <dl class="dl">
        <dt>Name</dt><dd><c:out value="${p.emergencyName}"/></dd>
        <dt>Relationship</dt><dd><c:out value="${p.emergencyRelationship}"/></dd>
        <dt>Phone</dt><dd><c:out value="${p.emergencyPhone}"/></dd>
        <dt>Alternate Phone</dt><dd><c:out value="${p.emergencyAltPhone}"/></dd>
        <dt>Address</dt><dd><c:out value="${p.emergencyAddress}"/></dd>
    </dl>
</section>

<section class="card">
    <h2>Insurance</h2>
    <c:choose>
        <c:when test="${p.hasInsurance}">
            <dl class="dl">
                <dt>Provider</dt><dd><c:out value="${p.insuranceProvider}"/></dd>
                <dt>Policy Number</dt><dd><c:out value="${p.policyNumber}"/></dd>
                <dt>Group Number</dt><dd><c:out value="${p.groupNumber}"/></dd>
                <dt>Coverage Type</dt><dd><c:out value="${p.coverageType}"/></dd>
                <dt>Policy Holder</dt><dd><c:out value="${p.policyHolderName}"/></dd>
                <dt>Relationship</dt><dd><c:out value="${p.holderRelationship}"/></dd>
                <dt>Valid From</dt><dd><c:out value="${p.policyValidFrom}"/></dd>
                <dt>Valid To</dt><dd><c:out value="${p.policyValidTo}"/></dd>
                <dt>Sum Insured</dt><dd><fmt:formatNumber value="${p.sumInsured}" minFractionDigits="2"/></dd>
            </dl>
        </c:when>
        <c:otherwise><p class="muted">No insurance.</p></c:otherwise>
    </c:choose>
</section>

<section class="card">
    <h2>Medical Information</h2>
    <dl class="dl">
        <dt>Height (cm)</dt><dd><c:out value="${p.heightCm}"/></dd>
        <dt>Weight (kg)</dt><dd><c:out value="${p.weightKg}"/></dd>
        <dt>BMI</dt><dd><c:out value="${p.bmi}"/></dd>
        <dt>Vaccination</dt><dd><c:out value="${p.vaccinationStatus}"/></dd>
        <dt>Smoking</dt><dd><c:out value="${p.smokingStatus}"/></dd>
        <dt>Alcohol</dt><dd><c:out value="${p.alcoholUse}"/></dd>
        <dt>Disability</dt><dd><c:out value="${p.disability}"/></dd>
        <dt>Allergies</dt><dd><c:out value="${p.knownAllergies}"/></dd>
        <dt>Chronic Conditions</dt><dd><c:out value="${p.chronicConditions}"/></dd>
        <dt>Current Medications</dt><dd><c:out value="${p.currentMedications}"/></dd>
        <dt>Past Surgeries</dt><dd><c:out value="${p.pastSurgeries}"/></dd>
        <dt>Family History</dt><dd><c:out value="${p.familyHistory}"/></dd>
    </dl>
</section>

<section class="card">
    <h2>Visit / Consultation</h2>
    <dl class="dl">
        <dt>Department</dt><dd><c:out value="${p.deptName}"/></dd>
        <dt>Consulting Doctor</dt><dd><c:out value="${p.consultingDoctor}"/></dd>
        <dt>Visit Type</dt><dd><c:out value="${p.visitType}"/></dd>
        <dt>Visit Date</dt><dd><c:out value="${p.visitDate}"/></dd>
        <dt>Priority</dt><dd><c:out value="${p.priority}"/></dd>
        <dt>Referral Source</dt><dd><c:out value="${p.referralSource}"/></dd>
        <dt>Referred By</dt><dd><c:out value="${p.referredBy}"/></dd>
        <dt>Symptom Duration</dt><dd><c:out value="${p.symptomDuration}"/></dd>
        <dt>Chief Complaint</dt><dd><c:out value="${p.chiefComplaint}"/></dd>
        <dt>Payment Mode</dt><dd><c:out value="${p.paymentMode}"/></dd>
        <dt>Registration Fee</dt><dd><fmt:formatNumber value="${p.registrationFee}" minFractionDigits="2"/></dd>
        <dt>Remarks</dt><dd><c:out value="${p.remarks}"/></dd>
    </dl>
</section>

<c:if test="${not empty p.customValues}">
<section class="card">
    <h2>Additional Details</h2>
    <dl class="dl">
        <c:forEach var="d" items="${customFieldDefs}">
            <c:if test="${not empty p.customValues[d.key]}">
                <dt><c:out value="${d.label}"/></dt>
                <dd><c:choose>
                    <c:when test="${d.type == 'YES_NO'}">${p.customValues[d.key] == 'true' ? 'Yes' : 'No'}</c:when>
                    <c:otherwise><c:out value="${p.customValues[d.key]}"/></c:otherwise>
                </c:choose></dd>
            </c:if>
        </c:forEach>
    </dl>
</section>
</c:if>

<%@ include file="common/footer.jspf" %>
