<%@ include file="common/taglibs.jspf" %>
<c:set var="pageTitle" value="Dashboard"/><c:set var="activeNav" value="home"/>
<%@ include file="common/header.jspf" %>

<c:if test="${not empty message}"><div class="alert alert-success"><c:out value="${message}"/></div></c:if>
<h1>Dashboard <span class="muted">&middot; <c:out value="${tenant.active.name}"/></span></h1>
<div class="stats">
    <div class="stat"><div class="stat-num"><fmt:formatNumber value="${counts.total}"/></div><div>Total Patients</div></div>
    <div class="stat"><div class="stat-num"><fmt:formatNumber value="${counts.today}"/></div><div>Registered Today</div></div>
    <div class="stat"><div class="stat-num"><fmt:formatNumber value="${counts.month}"/></div><div>This Month</div></div>
</div>

<div class="tiles">
    <c:if test="${tenant.allowedHere('REGISTER')}">
    <a class="tile" href="${ctx}/patient/new">
        <h3>New Registration</h3>
        <p>Register a new patient: personal details, contact, emergency contact, insurance, medical history and first visit.</p>
    </a>
    </c:if>
    <c:if test="${tenant.allowedHere('LEGACY_REPORTS')}">
    <a class="tile" href="${ctx}/reports/register">
        <h3>Report 1 &middot; Patient Register</h3>
        <p>List of registered patients with filters by date, department, gender and category. Download as Excel or PDF.</p>
    </a>
    <a class="tile" href="${ctx}/reports/summary">
        <h3>Report 2 &middot; Department Summary</h3>
        <p>Department-wise counts by gender, visit type and insurance, with average age and fee collected. Excel / PDF.</p>
    </a>
    </c:if>
    <c:if test="${tenant.allowedAnywhere('ANALYTICS')}">
    <a class="tile" href="${ctx}/app/">
        <h3>Analytics</h3>
        <p>Build your own reports: any columns, filters, totals and charts<c:if test="${tenant.multiHospital}">, across your hospitals</c:if>. Excel, PDF and CSV.</p>
    </a>
    </c:if>
</div>

<%@ include file="common/footer.jspf" %>
