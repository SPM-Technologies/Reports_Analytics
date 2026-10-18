<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="${empty role ? 'New role' : 'Edit role'}"/><c:set var="activeNav" value="admin"/><c:set var="adminTab" value="roles"/>
<%@ include file="../common/header.jspf" %>

<h1><c:out value="${empty role ? 'New role' : 'Edit role'}"/></h1>
<%@ include file="subnav.jspf" %>

<form method="post" action="${ctx}/admin/roles/${empty role ? 'new' : role.key}">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <section class="card">
        <div class="grid">
            <div class="f"><label>Hospital <span class="req">*</span></label>
                <c:choose>
                    <c:when test="${empty role}">
                        <select name="hospitalId" required>
                            <c:forEach var="h" items="${hospitals}"><option value="${h.hospitalId}"><c:out value="${h.code} - ${h.name}"/></option></c:forEach>
                        </select>
                    </c:when>
                    <c:otherwise><c:forEach var="h" items="${hospitals}"><c:if test="${h.hospitalId == role.hospitalId}">
                        <input value="${fn:escapeXml(h.code)} - ${fn:escapeXml(h.name)}" readonly></c:if></c:forEach></c:otherwise>
                </c:choose></div>
            <div class="f span2"><label>Role name <span class="req">*</span></label>
                <input name="label" value="${fn:escapeXml(label)}" maxlength="60" required></div>
        </div>
        <h2>Permissions</h2>
        <c:forEach var="p" items="${permissions}">
            <div class="f check"><label><input type="checkbox" name="permissions" value="${p}"
                ${chosen.contains(p.name()) ? 'checked' : ''}> <c:out value="${p.label}"/></label></div>
        </c:forEach>
    </section>
    <div class="actions">
        <button type="submit" class="btn btn-primary">Save</button>
        <a class="btn" href="${ctx}/admin/roles">Cancel</a>
    </div>
</form>
<c:if test="${not empty role}">
    <form method="post" action="${ctx}/admin/roles/${role.key}/delete" data-confirm="Delete this role?">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
        <button type="submit" class="btn">Delete role</button>
    </form>
</c:if>

<%@ include file="../common/footer.jspf" %>
