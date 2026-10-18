<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="${empty hospital ? 'New hospital' : 'Edit hospital'}"/><c:set var="activeNav" value="admin"/><c:set var="adminTab" value="hospitals"/>
<%@ include file="../common/header.jspf" %>

<h1><c:out value="${empty hospital ? 'New hospital' : 'Edit hospital '}"/><c:out value="${hospital.code}"/></h1>
<%@ include file="subnav.jspf" %>

<form method="post" action="${ctx}/admin/hospitals/${empty hospital ? 'new' : hospital.hospitalId}" autocomplete="off">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <section class="card">
        <div class="grid">
            <div class="f"><label>Code <span class="req">*</span></label>
                <c:choose>
                    <c:when test="${empty hospital}"><input name="code" value="${fn:escapeXml(code)}" maxlength="6" required
                        pattern="[A-Za-z][A-Za-z0-9]{1,5}" title="2-6 letters or digits, starting with a letter"></c:when>
                    <c:otherwise><input value="${fn:escapeXml(hospital.code)}" readonly></c:otherwise>
                </c:choose></div>
            <div class="f span2"><label>Name <span class="req">*</span></label>
                <input name="name" value="${fn:escapeXml(empty hospital ? name : hospital.name)}" maxlength="120" required></div>
            <div class="f"><label>City</label>
                <input name="city" value="${fn:escapeXml(empty hospital ? city : hospital.city)}" maxlength="60"></div>
            <c:if test="${not empty hospital}">
                <div class="f check"><label><input type="checkbox" name="active" value="true" ${hospital.active ? 'checked' : ''}> Active</label></div>
            </c:if>
        </div>
        <c:if test="${empty hospital}">
            <p class="hint">The new hospital gets the 10 default departments. You become its administrator and can then add its users.</p>
        </c:if>
        <c:if test="${not empty hospital}">
            <p class="hint">An inactive hospital disappears from its users' hospital list; its data is kept.</p>
        </c:if>
    </section>
    <div class="actions">
        <button type="submit" class="btn btn-primary">Save</button>
        <a class="btn" href="${ctx}/admin/hospitals">Cancel</a>
    </div>
</form>

<%@ include file="../common/footer.jspf" %>
