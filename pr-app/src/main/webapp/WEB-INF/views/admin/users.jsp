<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="Users"/><c:set var="activeNav" value="admin"/><c:set var="adminTab" value="users"/>
<%@ include file="../common/header.jspf" %>

<h1>Administration</h1>
<%@ include file="subnav.jspf" %>

<div class="toolbar">
    <a class="btn btn-primary" href="${ctx}/admin/users/new">New user</a>
    <span class="muted">Users of:
        <c:forEach var="h" items="${hospitals}" varStatus="s"><c:out value="${h.code}"/>${s.last ? '' : ', '}</c:forEach></span>
</div>
<div class="table-wrap">
<table class="data">
    <thead><tr><th>Username</th><th>Name</th><th>Hospitals and roles</th><th>Status</th><th>Last sign-in</th><th></th></tr></thead>
    <tbody>
    <c:forEach var="u" items="${users}">
        <tr>
            <td><c:out value="${u.username}"/><c:if test="${u.superAdmin}"> <span class="tag">super admin</span></c:if></td>
            <td><c:out value="${u.fullName}"/></td>
            <td><c:forEach var="a" items="${u.assignments}"><span class="tag"><c:out value="${a.hospitalCode}: ${a.role.label}"/></span></c:forEach></td>
            <td><c:choose>
                <c:when test="${not u.active}"><span class="tag warn">inactive</span></c:when>
                <c:when test="${blocked.contains(u.username)}"><span class="tag warn">sign-in blocked</span></c:when>
                <c:otherwise>active</c:otherwise>
            </c:choose></td>
            <td><c:out value="${u.lastLoginAt}"/></td>
            <td><a href="${ctx}/admin/users/${u.userId}">Edit</a></td>
        </tr>
    </c:forEach>
    </tbody>
</table>
</div>

<%@ include file="../common/footer.jspf" %>
