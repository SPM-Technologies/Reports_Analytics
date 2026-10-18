<%@ include file="common/taglibs.jspf" %>
<c:set var="pageTitle" value="Patient Register"/><c:set var="activeNav" value="register"/>
<%@ include file="common/header.jspf" %>

<h1>Report 1 &middot; Patient Registration Register</h1>

<form:form modelAttribute="filter" method="get" action="${ctx}/reports/register" cssClass="card filters" id="filterForm">
    <div class="f"><label>From Date</label><form:input path="fromDate" type="date"/></div>
    <div class="f"><label>To Date</label><form:input path="toDate" type="date"/></div>
    <div class="f"><label>Department</label>
        <form:select path="deptId"><form:option value="">All</form:option>
            <form:options items="${departments}" itemValue="deptId" itemLabel="deptName"/></form:select></div>
    <div class="f"><label>Gender</label>
        <form:select path="gender"><form:option value="">All</form:option><form:options items="${lk.genders}"/></form:select></div>
    <div class="f"><label>Category</label>
        <form:select path="patientCategory"><form:option value="">All</form:option><form:options items="${lk.categories}"/></form:select></div>
    <div class="f"><label>Search</label><form:input path="keyword" placeholder="Reg no / name / mobile"/></div>
    <div class="f filter-actions">
        <button type="submit" class="btn btn-primary">Search</button>
        <a href="${ctx}/reports/register" class="btn">Clear</a>
    </div>
</form:form>

<c:url var="exportUrl" value="/reports/register/export">
    <c:param name="fromDate" value="${filter.fromDate}"/>
    <c:param name="toDate" value="${filter.toDate}"/>
    <c:param name="deptId" value="${filter.deptId}"/>
    <c:param name="gender" value="${filter.gender}"/>
    <c:param name="patientCategory" value="${filter.patientCategory}"/>
    <c:param name="keyword" value="${filter.keyword}"/>
</c:url>

<div class="toolbar">
    <div><strong><fmt:formatNumber value="${total}"/></strong> record(s) found
        <c:if test="${total > 0}">&middot; page ${filter.page} of ${pages}</c:if></div>
    <div>
        <c:if test="${tenant.allowedHere('EXPORT')}">
        <a class="btn btn-excel" href="${exportUrl}&format=excel">&#8595; Excel</a>
        <a class="btn btn-pdf" href="${exportUrl}&format=pdf">&#8595; PDF</a>
        </c:if>
    </div>
</div>

<div class="table-wrap">
<table class="report">
    <thead><tr><th>#</th><c:forEach var="h" items="${headers}"><th>${h}</th></c:forEach></tr></thead>
    <tbody>
    <c:forEach var="r" items="${rows}" varStatus="s">
        <tr>
            <td class="num">${(filter.page - 1) * filter.pageSize + s.count}</td>
            <td><c:choose><c:when test="${canViewPatient}"><a href="${ctx}/patient/${r[0]}"><c:out value="${r[1]}"/></a></c:when><c:otherwise><c:out value="${r[1]}"/></c:otherwise></c:choose></td>
            <td><c:out value="${r[2]}"/></td>
            <td><c:out value="${r[3]}"/></td>
            <td><c:out value="${r[4]}"/></td>
            <td class="num">${r[5]}</td>
            <td><c:out value="${r[6]}"/></td>
            <td><c:out value="${r[7]}"/></td>
            <td><c:out value="${r[8]}"/></td>
            <td><c:out value="${r[9]}"/></td>
            <td><c:out value="${r[10]}"/></td>
            <td><c:out value="${r[11]}"/></td>
            <td><c:out value="${r[12]}"/></td>
            <td><c:out value="${r[13]}"/></td>
            <td class="num"><fmt:formatNumber value="${r[14]}" minFractionDigits="2"/></td>
        </tr>
    </c:forEach>
    <c:if test="${empty rows}"><tr><td colspan="15" class="empty">No records found.</td></tr></c:if>
    </tbody>
</table>
</div>

<c:if test="${pages > 1}">
<div class="pager">
    <c:url var="pageBase" value="/reports/register">
        <c:param name="fromDate" value="${filter.fromDate}"/>
        <c:param name="toDate" value="${filter.toDate}"/>
        <c:param name="deptId" value="${filter.deptId}"/>
        <c:param name="gender" value="${filter.gender}"/>
        <c:param name="patientCategory" value="${filter.patientCategory}"/>
        <c:param name="keyword" value="${filter.keyword}"/>
    </c:url>
    <c:if test="${filter.page > 1}">
        <a class="btn" href="${pageBase}&page=1">&laquo; First</a>
        <a class="btn" href="${pageBase}&page=${filter.page - 1}">&lsaquo; Prev</a>
    </c:if>
    <span>Page ${filter.page} / ${pages}</span>
    <c:if test="${filter.page < pages}">
        <a class="btn" href="${pageBase}&page=${filter.page + 1}">Next &rsaquo;</a>
        <a class="btn" href="${pageBase}&page=${pages}">Last &raquo;</a>
    </c:if>
</div>
</c:if>

<%@ include file="common/footer.jspf" %>
