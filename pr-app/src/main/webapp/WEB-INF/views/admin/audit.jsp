<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="Audit log"/><c:set var="activeNav" value="admin"/><c:set var="adminTab" value="audit"/>
<%@ include file="../common/header.jspf" %>

<h1>Administration</h1>
<%@ include file="subnav.jspf" %>

<form method="get" action="${ctx}/admin/audit" class="filters">
    <div class="f"><label>Action</label>
        <select name="action"><option value="">All</option>
            <c:forEach var="a" items="${actions}"><option value="${a}" ${a == action ? 'selected' : ''}>${a}</option></c:forEach>
        </select></div>
    <div class="f"><label>Username</label><input name="user" value="${fn:escapeXml(user)}" maxlength="40"></div>
    <div class="filter-actions"><button class="btn btn-primary">Filter</button> <a class="btn" href="${ctx}/admin/audit">Clear</a></div>
</form>
<p class="muted">Latest 300 entries<c:if test="${not tenant.superAdmin}"> of your hospitals</c:if>.
    The log cannot be changed or deleted by the application.</p>
<div class="table-wrap">
<table class="data">
    <thead><tr><th>Time</th><th>User</th><th>Hospital</th><th>Action</th><th>Detail</th><th>Address</th></tr></thead>
    <tbody>
    <c:forEach var="e" items="${entries}">
        <tr>
            <td><c:out value="${e.at}"/></td>
            <td><c:out value="${e.username}"/></td>
            <td><c:out value="${e.hospitalCode}"/></td>
            <td><c:out value="${e.action}"/></td>
            <td><c:out value="${e.detail}"/></td>
            <td><c:out value="${e.clientAddress}"/></td>
        </tr>
    </c:forEach>
    </tbody>
</table>
</div>

<%@ include file="../common/footer.jspf" %>
