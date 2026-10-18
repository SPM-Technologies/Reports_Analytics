<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="Hospitals"/><c:set var="activeNav" value="admin"/><c:set var="adminTab" value="hospitals"/>
<%@ include file="../common/header.jspf" %>

<h1>Administration</h1>
<%@ include file="subnav.jspf" %>

<div class="toolbar"><a class="btn btn-primary" href="${ctx}/admin/hospitals/new">New hospital</a></div>
<div class="table-wrap">
<table class="data">
    <thead><tr><th>Code</th><th>Name</th><th>City</th><th>Status</th><th>Added</th><th></th></tr></thead>
    <tbody>
    <c:forEach var="h" items="${hospitals}">
        <tr>
            <td><c:out value="${h.code}"/></td>
            <td><c:out value="${h.name}"/></td>
            <td><c:out value="${h.city}"/></td>
            <td>${h.active ? 'active' : '<span class="tag warn">inactive</span>'}</td>
            <td><c:out value="${h.createdAt}"/></td>
            <td><a href="${ctx}/admin/hospitals/${h.hospitalId}">Edit</a></td>
        </tr>
    </c:forEach>
    </tbody>
</table>
</div>
<p class="hint">Each hospital sees only its own patients, departments, reports and users. The code prefixes the
    hospital's registration numbers (e.g. H001-REG20260000001) and cannot be changed.</p>

<%@ include file="../common/footer.jspf" %>
