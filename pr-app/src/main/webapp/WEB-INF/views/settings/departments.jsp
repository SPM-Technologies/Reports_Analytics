<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="Departments and doctors"/><c:set var="activeNav" value="settings"/><c:set var="settingsTab" value="departments"/>
<%@ include file="../common/header.jspf" %>

<h1>Settings</h1>
<%@ include file="subnav.jspf" %>

<section class="card">
    <h2>Add a department</h2>
    <form method="post" action="${ctx}/settings/departments/new" class="filters">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
        <div class="f"><label>Code</label><input name="code" maxlength="10" required pattern="[A-Za-z0-9]{2,10}"></div>
        <div class="f"><label>Name</label><input name="name" maxlength="100" required></div>
        <div class="filter-actions"><button type="submit" class="btn btn-primary">Add</button></div>
    </form>
</section>

<p class="hint">Doctors: one name per line; they are suggested on the registration form for the chosen department.
    Departments are never deleted (reports keep them); deactivate one to remove it from the form.</p>
<div class="grid">
<c:forEach var="d" items="${departments}">
    <section class="card span2">
        <form method="post" action="${ctx}/settings/departments/${d.deptId}">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
            <h2><c:out value="${d.code}"/> <c:if test="${not d.active}"><span class="tag warn">inactive</span></c:if></h2>
            <div class="grid">
                <div class="f span2"><label>Name</label><input name="name" value="${fn:escapeXml(d.name)}" maxlength="100" required></div>
                <div class="f check"><label><input type="checkbox" name="active" value="true" ${d.active ? 'checked' : ''}> Active</label></div>
                <div class="f span4"><label>Doctors</label>
                    <textarea name="doctors" rows="3" style="width:100%"><c:out value="${doctors[d.deptId]}"/></textarea></div>
            </div>
            <div class="actions"><button type="submit" class="btn btn-primary">Save</button></div>
        </form>
    </section>
</c:forEach>
</div>

<%@ include file="../common/footer.jspf" %>
