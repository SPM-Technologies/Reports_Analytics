<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="Report fields"/><c:set var="activeNav" value="settings"/><c:set var="settingsTab" value="reportFields"/>
<%@ include file="../common/header.jspf" %>

<h1>Settings</h1>
<%@ include file="subnav.jspf" %>

<p class="hint">How each field of the Patients dataset appears in this hospital's Analytics reports, the classic Patient Register and Department Summary, and their downloads. Age Band and Reg Month follow Age and Reg Date.
    <strong>Masked</strong> shows only the first and last two characters. It suits free text such as names and numbers; fields with a short list of values (gender, city, ...), numbers and dates are hidden instead, because a masked value would still give them away.
    <strong>Administrators only</strong> hides the field from everyone without user-management rights. Hidden fields cannot be
    read through calculated fields either. Own form fields are configured on the Form fields tab.</p>
<form method="post" action="${ctx}/settings/report-fields">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <div class="table-wrap">
    <table class="data">
        <thead><tr><th>Field</th><th>Shown as</th><th>Label in reports (optional)</th></tr></thead>
        <tbody>
        <c:forEach var="f" items="${fields}">
            <c:set var="s" value="${current[f.name()]}"/>
            <tr>
                <td><c:out value="${f.label()}"/> <span class="muted"><code><c:out value="${f.name()}"/></code></span></td>
                <td><select name="visibility_${f.name()}">
                    <c:forEach var="v" items="${visibilities}">
                        <option value="${v}" ${(empty s and v == 'NORMAL') or s.visibility == v ? 'selected' : ''}>
                            <c:out value="${v == 'NORMAL' ? 'Normal' : v == 'MASKED' ? 'Masked' : v == 'HIDDEN' ? 'Hidden' : 'Administrators only'}"/></option>
                    </c:forEach>
                </select></td>
                <td><input name="label_${f.name()}" value="${fn:escapeXml(s.label)}" maxlength="60" placeholder="${fn:escapeXml(f.label())}"></td>
            </tr>
        </c:forEach>
        </tbody>
    </table>
    </div>
    <div class="actions"><button type="submit" class="btn btn-primary">Save</button></div>
</form>

<%@ include file="../common/footer.jspf" %>
