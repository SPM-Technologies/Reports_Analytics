<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="${empty existing ? 'Add a field' : 'Edit field'}"/><c:set var="activeNav" value="settings"/><c:set var="settingsTab" value="fields"/>
<%@ include file="../common/header.jspf" %>

<h1><c:out value="${empty existing ? 'Add a field' : 'Edit field'}"/></h1>
<%@ include file="subnav.jspf" %>

<form method="post" action="${ctx}/settings/fields/${empty existing ? 'new' : existing.key}">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <section class="card">
        <h2>Field</h2>
        <c:if test="${usage > 0}"><p class="hint"><fmt:formatNumber value="${usage}"/> patients have a value for this field: its type can no longer change.</p></c:if>
        <div class="grid">
            <div class="f"><label>Label <span class="req">*</span></label>
                <input name="label" value="${fn:escapeXml(field.label)}" maxlength="60" required></div>
            <div class="f"><label>Key <span class="req">*</span></label>
                <c:choose>
                    <c:when test="${empty existing}"><input name="key" value="${fn:escapeXml(field.key)}" maxlength="30" required
                        pattern="[a-z][a-z0-9_]{1,29}" title="lower-case letters, digits, _ ; e.g. abha_number"></c:when>
                    <c:otherwise><input value="${fn:escapeXml(field.key)}" readonly></c:otherwise>
                </c:choose>
                <small class="hint">Stored name, used in reports; cannot change later.</small></div>
            <div class="f"><label>Type <span class="req">*</span></label>
                <select name="type" id="cfType">
                    <c:forEach var="t" items="${types}"><option value="${t}" ${field.type == t.name() ? 'selected' : ''}><c:out value="${t.label}"/></option></c:forEach>
                </select></div>
            <div class="f"><label>Form section</label>
                <select name="section">
                    <c:forEach var="s" items="${sections}"><option value="${s}" ${field.section == s ? 'selected' : ''}>${s}</option></c:forEach>
                </select></div>
            <div class="f"><label>Order in section</label><input name="sortOrder" type="number" min="0" max="999" value="${field.sortOrder}"></div>
            <div class="f check"><label><input type="checkbox" name="required" value="true" ${field.required ? 'checked' : ''}> Required</label>
                <input type="hidden" name="_required" value="on"></div>
            <div class="f check"><label><input type="checkbox" name="active" value="true" ${field.active ? 'checked' : ''}> Active (shown on the form)</label>
                <input type="hidden" name="_active" value="on"></div>
            <div class="f"><label>Minimum (numbers)</label><input name="minValue" value="${fn:escapeXml(field.minValue)}" inputmode="decimal"></div>
            <div class="f"><label>Maximum (numbers)</label><input name="maxValue" value="${fn:escapeXml(field.maxValue)}" inputmode="decimal"></div>
            <div class="f"><label>Maximum length (text)</label><input name="maxLength" type="number" min="1" max="2000" value="${field.maxLength}"></div>
            <div class="f span2"><label>Values (dropdown fields: one per line)</label>
                <textarea name="options" rows="5"><c:out value="${field.options}"/></textarea></div>
            <div class="f span2"><label>Help text</label><input name="helpText" value="${fn:escapeXml(field.helpText)}" maxlength="200"></div>
        </div>
    </section>
    <section class="card">
        <h2>Reports</h2>
        <div class="grid">
            <div class="f check"><label><input type="checkbox" name="reportable" value="true" ${field.reportable ? 'checked' : ''}>
                Available in Analytics</label><input type="hidden" name="_reportable" value="on"></div>
            <div class="f"><label>Sensitivity</label>
                <select name="sensitivity">
                    <option value="NORMAL" ${field.sensitivity == 'NORMAL' ? 'selected' : ''}>Normal: shown as entered</option>
                    <option value="MASKED" ${field.sensitivity == 'MASKED' ? 'selected' : ''}>Masked: first and last 2 characters only (text fields)</option>
                    <option value="HIDDEN" ${field.sensitivity == 'HIDDEN' ? 'selected' : ''}>Hidden: never in reports</option>
                </select></div>
        </div>
        <p class="hint">A field appears in Analytics within seconds of being made available there, including the values entered before.</p>
    </section>
    <div class="actions">
        <button type="submit" class="btn btn-primary">Save</button>
        <a class="btn" href="${ctx}/settings/fields">Cancel</a>
    </div>
</form>

<%@ include file="../common/footer.jspf" %>
