<%@ include file="common/taglibs.jspf" %>
<c:set var="pageTitle" value="New Registration"/><c:set var="activeNav" value="new"/>
<%@ include file="common/header.jspf" %>

<h1>New Patient Registration</h1>
<c:if test="${not empty errorCount}">
    <div class="alert alert-error">Please correct the ${errorCount} highlighted field(s) below.</div>
</c:if>
<p class="hint">Fields marked <span class="req">*</span> are mandatory.</p>

<form:form modelAttribute="patient" action="${ctx}/patient/save" method="post" id="regForm">

<!-- ================= 1. Personal details ================= -->
<section class="card">
    <h2>1. Personal Details</h2>
    <div class="grid">
        <div class="f"><label>Registration Date</label>
            <form:input path="registrationDate" type="date"/><form:errors path="registrationDate" cssClass="err"/></div>
        <div class="f"><label>Patient Category <span class="req">*</span></label>
            <form:select path="patientCategory"><form:options items="${lk.categories}"/></form:select>
            <form:errors path="patientCategory" cssClass="err"/></div>
        <div class="f"><label>Title</label>
            <form:select path="title"><form:option value="">-- Select --</form:option><form:options items="${lk.titles}"/></form:select><form:errors path="title" cssClass="err"/></div>
        <div class="f"><label>First Name <span class="req">*</span></label>
            <form:input path="firstName" maxlength="60" required="required"/><form:errors path="firstName" cssClass="err"/></div>
        <c:if test="${not hiddenFields.contains('middleName')}">
        <div class="f"><label>Middle Name</label>
            <form:input path="middleName" maxlength="60"/><form:errors path="middleName" cssClass="err"/></div>
        </c:if>
        <div class="f"><label>Last Name <span class="req">*</span></label>
            <form:input path="lastName" maxlength="60" required="required"/><form:errors path="lastName" cssClass="err"/></div>
        <div class="f"><label>Gender <span class="req">*</span></label>
            <form:select path="gender" required="required"><form:option value="">-- Select --</form:option><form:options items="${lk.genders}"/></form:select>
            <form:errors path="gender" cssClass="err"/></div>
        <div class="f"><label>Date of Birth</label>
            <form:input path="dateOfBirth" type="date" id="dob"/><form:errors path="dateOfBirth" cssClass="err"/></div>
        <div class="f"><label>Age (years) <span class="req">*</span></label>
            <form:input path="ageYears" type="number" min="0" max="130" id="age"/><form:errors path="ageYears" cssClass="err"/></div>
        <c:if test="${not hiddenFields.contains('bloodGroup')}">
        <div class="f"><label>Blood Group</label>
            <form:select path="bloodGroup"><form:option value="">-- Select --</form:option><form:options items="${lk.bloodGroups}"/></form:select><form:errors path="bloodGroup" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('maritalStatus')}">
        <div class="f"><label>Marital Status</label>
            <form:select path="maritalStatus"><form:option value="">-- Select --</form:option><form:options items="${lk.maritalStatuses}"/></form:select><form:errors path="maritalStatus" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('nationality')}">
        <div class="f"><label>Nationality</label>
            <form:input path="nationality" maxlength="40"/><form:errors path="nationality" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('religion')}">
        <div class="f"><label>Religion</label>
            <form:select path="religion"><form:option value="">-- Select --</form:option><form:options items="${lk.religions}"/></form:select><form:errors path="religion" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('occupation')}">
        <div class="f"><label>Occupation</label>
            <form:input path="occupation" maxlength="60"/><form:errors path="occupation" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('nationalId')}">
        <div class="f"><label>Aadhaar No.</label>
            <form:input path="nationalId" maxlength="12" pattern="\d{12}" inputmode="numeric"/><form:errors path="nationalId" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('preferredLanguage')}">
        <div class="f"><label>Preferred Language</label>
            <form:select path="preferredLanguage"><form:option value="">-- Select --</form:option><form:options items="${lk.languages}"/></form:select><form:errors path="preferredLanguage" cssClass="err"/></div>
        </c:if>
    </div>
    <c:set var="cfSection" value="personal"/><%@ include file="common/custom-fields.jspf" %>
</section>

<!-- ================= 2. Contact & address ================= -->
<section class="card">
    <h2>2. Contact &amp; Address</h2>
    <div class="grid">
        <div class="f"><label>Mobile <span class="req">*</span></label>
            <form:input path="mobile" maxlength="10" inputmode="numeric" required="required"/><form:errors path="mobile" cssClass="err"/></div>
        <c:if test="${not hiddenFields.contains('alternatePhone')}">
        <div class="f"><label>Alternate Phone</label>
            <form:input path="alternatePhone" maxlength="10" inputmode="numeric"/><form:errors path="alternatePhone" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('email')}">
        <div class="f span2"><label>Email</label>
            <form:input path="email" type="email" maxlength="100"/><form:errors path="email" cssClass="err"/></div>
        </c:if>
        <div class="f span2"><label>Address Line 1 <span class="req">*</span></label>
            <form:input path="addressLine1" maxlength="150" required="required"/><form:errors path="addressLine1" cssClass="err"/></div>
        <c:if test="${not hiddenFields.contains('addressLine2')}">
        <div class="f span2"><label>Address Line 2</label>
            <form:input path="addressLine2" maxlength="150"/><form:errors path="addressLine2" cssClass="err"/></div>
        </c:if>
        <div class="f"><label>City <span class="req">*</span></label>
            <form:input path="city" maxlength="60" required="required"/><form:errors path="city" cssClass="err"/></div>
        <c:if test="${not hiddenFields.contains('district')}">
        <div class="f"><label>District</label>
            <form:input path="district" maxlength="60"/><form:errors path="district" cssClass="err"/></div>
        </c:if>
        <div class="f"><label>State <span class="req">*</span></label>
            <form:select path="state" required="required"><form:option value="">-- Select --</form:option><form:options items="${lk.states}"/></form:select>
            <form:errors path="state" cssClass="err"/></div>
        <div class="f"><label>Pincode <span class="req">*</span></label>
            <form:input path="pincode" maxlength="6" inputmode="numeric" required="required"/><form:errors path="pincode" cssClass="err"/></div>
        <div class="f"><label>Country <span class="req">*</span></label>
            <form:input path="country" maxlength="60"/><form:errors path="country" cssClass="err"/></div>
        <div class="f span2 check"><label><form:checkbox path="permSameAsCurrent" id="permSame"/> Permanent address same as current</label></div>
        <div class="f span4" id="permBox"><label>Permanent Address <span class="req">*</span></label>
            <form:textarea path="permanentAddress" rows="2" maxlength="300"/><form:errors path="permanentAddress" cssClass="err"/></div>
    </div>
    <c:set var="cfSection" value="contact"/><%@ include file="common/custom-fields.jspf" %>
</section>

<!-- ================= 3. Emergency contact ================= -->
<section class="card">
    <h2>3. Emergency Contact</h2>
    <div class="grid">
        <div class="f"><label>Contact Name <span class="req">*</span></label>
            <form:input path="emergencyName" maxlength="100" required="required"/><form:errors path="emergencyName" cssClass="err"/></div>
        <div class="f"><label>Relationship <span class="req">*</span></label>
            <form:select path="emergencyRelationship" required="required"><form:option value="">-- Select --</form:option><form:options items="${lk.relationships}"/></form:select>
            <form:errors path="emergencyRelationship" cssClass="err"/></div>
        <div class="f"><label>Phone <span class="req">*</span></label>
            <form:input path="emergencyPhone" maxlength="10" inputmode="numeric" required="required"/><form:errors path="emergencyPhone" cssClass="err"/></div>
        <c:if test="${not hiddenFields.contains('emergencyAltPhone')}">
        <div class="f"><label>Alternate Phone</label>
            <form:input path="emergencyAltPhone" maxlength="10" inputmode="numeric"/><form:errors path="emergencyAltPhone" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('emergencyAddress')}">
        <div class="f span4"><label>Address</label>
            <form:input path="emergencyAddress" maxlength="300"/><form:errors path="emergencyAddress" cssClass="err"/></div>
        </c:if>
    </div>
    <c:set var="cfSection" value="emergency"/><%@ include file="common/custom-fields.jspf" %>
</section>

<!-- ================= 4. Insurance ================= -->
<section class="card">
    <h2>4. Insurance</h2>
    <div class="grid">
        <div class="f span4 check"><label><form:checkbox path="hasInsurance" id="hasIns"/> Patient has health insurance</label></div>
    </div>
    <div class="grid" id="insBox">
        <div class="f"><label>Insurance Provider <span class="req">*</span></label>
            <form:input path="insuranceProvider" maxlength="100"/><form:errors path="insuranceProvider" cssClass="err"/></div>
        <div class="f"><label>Policy Number <span class="req">*</span></label>
            <form:input path="policyNumber" maxlength="40"/><form:errors path="policyNumber" cssClass="err"/></div>
        <c:if test="${not hiddenFields.contains('groupNumber')}">
        <div class="f"><label>Group Number</label>
            <form:input path="groupNumber" maxlength="40"/><form:errors path="groupNumber" cssClass="err"/></div>
        </c:if>
        <div class="f"><label>Coverage Type</label>
            <form:select path="coverageType"><form:option value="">-- Select --</form:option><form:options items="${lk.coverageTypes}"/></form:select><form:errors path="coverageType" cssClass="err"/></div>
        <c:if test="${not hiddenFields.contains('policyHolderName')}">
        <div class="f"><label>Policy Holder Name</label>
            <form:input path="policyHolderName" maxlength="100"/><form:errors path="policyHolderName" cssClass="err"/></div>
        </c:if>
        <div class="f"><label>Relationship to Holder</label>
            <form:select path="holderRelationship"><form:option value="">-- Select --</form:option><form:options items="${lk.holderRelationships}"/></form:select><form:errors path="holderRelationship" cssClass="err"/></div>
        <div class="f"><label>Valid From</label>
            <form:input path="policyValidFrom" type="date"/><form:errors path="policyValidFrom" cssClass="err"/></div>
        <div class="f"><label>Valid To <span class="req">*</span></label>
            <form:input path="policyValidTo" type="date"/><form:errors path="policyValidTo" cssClass="err"/></div>
        <div class="f"><label>Sum Insured (Rs)</label>
            <form:input path="sumInsured" type="number" step="0.01" min="0"/><form:errors path="sumInsured" cssClass="err"/></div>
    </div>
    <c:set var="cfSection" value="insurance"/><%@ include file="common/custom-fields.jspf" %>
</section>

<!-- ================= 5. Medical information ================= -->
<section class="card">
    <h2>5. Medical Information</h2>
    <div class="grid">
        <c:if test="${not hiddenFields.contains('heightCm')}">
        <div class="f"><label>Height (cm)</label>
            <form:input path="heightCm" type="number" step="0.1" id="height"/><form:errors path="heightCm" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('weightKg')}">
        <div class="f"><label>Weight (kg)</label>
            <form:input path="weightKg" type="number" step="0.1" id="weight"/><form:errors path="weightKg" cssClass="err"/></div>
        </c:if>
        <div class="f"><label>BMI (auto)</label>
            <input type="text" id="bmi" readonly="readonly" class="readonly"/></div>
        <c:if test="${not hiddenFields.contains('vaccinationStatus')}">
        <div class="f"><label>Vaccination Status</label>
            <form:select path="vaccinationStatus"><form:option value="">-- Select --</form:option><form:options items="${lk.vaccinationStatuses}"/></form:select><form:errors path="vaccinationStatus" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('smokingStatus')}">
        <div class="f"><label>Smoking</label>
            <form:select path="smokingStatus"><form:option value="">-- Select --</form:option><form:options items="${lk.smokingStatuses}"/></form:select><form:errors path="smokingStatus" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('alcoholUse')}">
        <div class="f"><label>Alcohol</label>
            <form:select path="alcoholUse"><form:option value="">-- Select --</form:option><form:options items="${lk.alcoholUses}"/></form:select><form:errors path="alcoholUse" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('disability')}">
        <div class="f span2"><label>Disability (if any)</label>
            <form:input path="disability" maxlength="100"/><form:errors path="disability" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('knownAllergies')}">
        <div class="f span2"><label>Known Allergies</label>
            <form:textarea path="knownAllergies" rows="2" maxlength="300"/><form:errors path="knownAllergies" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('chronicConditions')}">
        <div class="f span2"><label>Chronic Conditions</label>
            <form:textarea path="chronicConditions" rows="2" maxlength="300"/><form:errors path="chronicConditions" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('currentMedications')}">
        <div class="f span2"><label>Current Medications</label>
            <form:textarea path="currentMedications" rows="2" maxlength="300"/><form:errors path="currentMedications" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('pastSurgeries')}">
        <div class="f span2"><label>Past Surgeries</label>
            <form:textarea path="pastSurgeries" rows="2" maxlength="300"/><form:errors path="pastSurgeries" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('familyHistory')}">
        <div class="f span4"><label>Family History</label>
            <form:textarea path="familyHistory" rows="2" maxlength="300"/><form:errors path="familyHistory" cssClass="err"/></div>
        </c:if>
    </div>
    <c:set var="cfSection" value="medical"/><%@ include file="common/custom-fields.jspf" %>
</section>

<!-- ================= 6. Visit details ================= -->
<section class="card">
    <h2>6. Visit / Consultation</h2>
    <div class="grid">
        <div class="f"><label>Department <span class="req">*</span></label>
            <form:select path="deptId" required="required">
                <form:option value="">-- Select --</form:option>
                <form:options items="${departments}" itemValue="deptId" itemLabel="deptName"/>
            </form:select><form:errors path="deptId" cssClass="err"/></div>
        <div class="f"><label>Consulting Doctor <span class="req">*</span></label>
            <form:input path="consultingDoctor" maxlength="100" required="required" list="doctorList" autocomplete="off"/>
            <datalist id="doctorList"><c:forEach var="doc" items="${doctors}"><option value="${fn:escapeXml(doc.name)}" data-dept="${doc.deptId}"></c:forEach></datalist><form:errors path="consultingDoctor" cssClass="err"/></div>
        <div class="f"><label>Visit Type <span class="req">*</span></label>
            <form:select path="visitType"><form:options items="${lk.visitTypes}"/></form:select><form:errors path="visitType" cssClass="err"/></div>
        <div class="f"><label>Visit Date</label>
            <form:input path="visitDate" type="date"/><form:errors path="visitDate" cssClass="err"/></div>
        <div class="f"><label>Priority <span class="req">*</span></label>
            <form:select path="priority"><form:options items="${lk.priorities}"/></form:select><form:errors path="priority" cssClass="err"/></div>
        <c:if test="${not hiddenFields.contains('referralSource')}">
        <div class="f"><label>Referral Source</label>
            <form:select path="referralSource"><form:option value="">-- Select --</form:option><form:options items="${lk.referralSources}"/></form:select><form:errors path="referralSource" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('referredBy')}">
        <div class="f"><label>Referred By</label>
            <form:input path="referredBy" maxlength="100"/><form:errors path="referredBy" cssClass="err"/></div>
        </c:if>
        <c:if test="${not hiddenFields.contains('symptomDuration')}">
        <div class="f"><label>Symptom Duration</label>
            <form:input path="symptomDuration" maxlength="30" placeholder="e.g. 3 days"/><form:errors path="symptomDuration" cssClass="err"/></div>
        </c:if>
        <div class="f span4"><label>Chief Complaint <span class="req">*</span></label>
            <form:textarea path="chiefComplaint" rows="2" maxlength="300" required="required"/><form:errors path="chiefComplaint" cssClass="err"/></div>
        <div class="f"><label>Payment Mode <span class="req">*</span></label>
            <form:select path="paymentMode" required="required"><form:option value="">-- Select --</form:option><form:options items="${lk.paymentModes}"/></form:select>
            <form:errors path="paymentMode" cssClass="err"/></div>
        <div class="f"><label>Registration Fee (Rs)</label>
            <form:input path="registrationFee" type="number" step="0.01" min="0"/><form:errors path="registrationFee" cssClass="err"/></div>
        <c:if test="${not hiddenFields.contains('remarks')}">
        <div class="f span2"><label>Remarks</label>
            <form:input path="remarks" maxlength="300"/><form:errors path="remarks" cssClass="err"/></div>
        </c:if>
    </div>
    <c:set var="cfSection" value="visit"/><%@ include file="common/custom-fields.jspf" %>
</section>

<c:if test="${not empty customFields['additional']}">
<section class="card">
    <h2>7. Additional Details</h2>
    <c:set var="cfSection" value="additional"/><%@ include file="common/custom-fields.jspf" %>
</section>
</c:if>

<div class="actions">
    <button type="submit" class="btn btn-primary">Register Patient</button>
    <button type="reset" class="btn">Reset</button>
</div>
</form:form>

<script src="${ctx}/resources/js/patient-form.js"></script>
<%@ include file="common/footer.jspf" %>
