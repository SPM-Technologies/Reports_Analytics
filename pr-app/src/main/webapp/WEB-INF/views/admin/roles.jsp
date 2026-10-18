<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="Roles"/><c:set var="activeNav" value="admin"/><c:set var="adminTab" value="roles"/>
<%@ include file="../common/header.jspf" %>

<h1>Administration</h1>
<%@ include file="subnav.jspf" %>

<div class="toolbar"><a class="btn btn-primary" href="${ctx}/admin/roles/new">New role</a>
    <span class="muted">A role is a set of permissions; users get one role per hospital.</span></div>
<div class="table-wrap">
<table class="data">
    <thead><tr><th>Role</th><th>Hospital</th>
        <c:forEach var="p" items="${permissions}"><th title="${fn:escapeXml(p.label)}"><c:out value="${p.label}"/></th></c:forEach><th></th></tr></thead>
    <tbody>
    <c:forEach var="r" items="${roles}">
        <tr>
            <td><c:out value="${r.label}"/><c:if test="${r.builtin}"> <span class="tag">built-in</span></c:if></td>
            <td><c:choose><c:when test="${empty r.hospitalId}">all</c:when><c:otherwise>
                <c:forEach var="h" items="${hospitals}"><c:if test="${h.hospitalId == r.hospitalId}"><c:out value="${h.code}"/></c:if></c:forEach>
            </c:otherwise></c:choose></td>
            <c:forEach var="p" items="${permissions}"><td>${r.has(p.name()) ? '&#10003;' : ''}</td></c:forEach>
            <td><c:if test="${not r.builtin}"><a href="${ctx}/admin/roles/${r.key}">Edit</a></c:if></td>
        </tr>
    </c:forEach>
    </tbody>
</table>
</div>

<%@ include file="../common/footer.jspf" %>
