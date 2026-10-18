<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="Form fields"/><c:set var="activeNav" value="settings"/><c:set var="settingsTab" value="fields"/>
<%@ include file="../common/header.jspf" %>

<h1>Settings</h1>
<%@ include file="subnav.jspf" %>

<section class="card">
    <h2>Own fields on the registration form</h2>
    <div class="toolbar"><a class="btn btn-primary" href="${ctx}/settings/fields/new">Add a field</a>
        <span class="muted">Fields are never deleted (patients keep their values); deactivate a field to remove it from the form.</span></div>
    <div class="table-wrap">
    <table class="data">
        <thead><tr><th>Label</th><th>Key</th><th>Type</th><th>Section</th><th>Required</th><th>In reports</th><th>Status</th><th></th></tr></thead>
        <tbody>
        <c:forEach var="f" items="${fields}">
            <tr>
                <td><c:out value="${f.label}"/></td>
                <td><code><c:out value="${f.key}"/></code></td>
                <td><c:out value="${f.type.label}"/></td>
                <td><c:out value="${f.section}"/></td>
                <td>${f.required ? 'yes' : ''}</td>
                <td><c:choose><c:when test="${f.reportable and f.sensitivity != 'HIDDEN'}"><c:out value="${f.sensitivity == 'MASKED' ? 'masked' : 'yes'}"/></c:when><c:otherwise>no</c:otherwise></c:choose></td>
                <td>${f.active ? 'active' : '<span class="tag warn">inactive</span>'}</td>
                <td><a href="${ctx}/settings/fields/${f.key}">Edit</a></td>
            </tr>
        </c:forEach>
        <c:if test="${empty fields}"><tr><td colspan="8" class="muted">No own fields yet.</td></tr></c:if>
        </tbody>
    </table>
    </div>
</section>

<section class="card">
    <h2>Standard fields this hospital does not use</h2>
    <p class="hint">Ticked fields are hidden from the registration form and are not stored. Mandatory fields cannot be hidden.</p>
    <form method="post" action="${ctx}/settings/fields/hidden">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
        <div class="grid">
            <c:forEach var="h" items="${hideable}">
                <div class="f check"><label><input type="checkbox" name="hidden" value="${h.key}" ${hidden.contains(h.key) ? 'checked' : ''}>
                    <c:out value="${h.value}"/></label></div>
            </c:forEach>
        </div>
        <div class="actions"><button type="submit" class="btn btn-primary">Save</button></div>
    </form>
</section>

<%@ include file="../common/footer.jspf" %>
