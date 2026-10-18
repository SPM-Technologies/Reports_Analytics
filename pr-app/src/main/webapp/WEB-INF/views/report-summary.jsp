<%@ include file="common/taglibs.jspf" %>
<c:set var="pageTitle" value="Department Summary"/><c:set var="activeNav" value="summary"/>
<%@ include file="common/header.jspf" %>

<h1>Report 2 &middot; Department-wise Registration Summary</h1>

<form:form modelAttribute="filter" method="get" action="${ctx}/reports/summary" cssClass="card filters">
    <div class="f"><label>From Date</label><form:input path="fromDate" type="date"/></div>
    <div class="f"><label>To Date</label><form:input path="toDate" type="date"/></div>
    <div class="f"><label>Gender</label>
        <form:select path="gender"><form:option value="">All</form:option><form:options items="${lk.genders}"/></form:select></div>
    <div class="f"><label>Category</label>
        <form:select path="patientCategory"><form:option value="">All</form:option><form:options items="${lk.categories}"/></form:select></div>
    <div class="f filter-actions">
        <button type="submit" class="btn btn-primary">Show</button>
        <a href="${ctx}/reports/summary" class="btn">Clear</a>
    </div>
</form:form>

<c:url var="exportUrl" value="/reports/summary/export">
    <c:param name="fromDate" value="${filter.fromDate}"/>
    <c:param name="toDate" value="${filter.toDate}"/>
    <c:param name="gender" value="${filter.gender}"/>
    <c:param name="patientCategory" value="${filter.patientCategory}"/>
</c:url>

<div class="toolbar">
    <div>Registrations grouped by department of the registration visit.</div>
    <div>
        <c:if test="${tenant.allowedHere('EXPORT')}">
        <a class="btn btn-excel" href="${exportUrl}&format=excel">&#8595; Excel</a>
        <a class="btn btn-pdf" href="${exportUrl}&format=pdf">&#8595; PDF</a>
        </c:if>
    </div>
</div>

<div class="table-wrap">
<table class="report">
    <thead><tr><c:forEach var="h" items="${headers}"><th>${h}</th></c:forEach></tr></thead>
    <tbody>
    <c:forEach var="r" items="${rows}">
        <tr>
            <td><c:out value="${r[0]}"/></td>
            <c:forEach begin="1" end="8" var="i"><td class="num"><fmt:formatNumber value="${r[i]}"/></td></c:forEach>
            <td class="num">${r[9]}</td>
            <td class="num"><fmt:formatNumber value="${r[10]}" minFractionDigits="2" maxFractionDigits="2"/></td>
        </tr>
    </c:forEach>
    </tbody>
    <tfoot>
        <tr>
            <td>${totals[0]}</td>
            <c:forEach begin="1" end="8" var="i"><td class="num"><fmt:formatNumber value="${totals[i]}"/></td></c:forEach>
            <td class="num">${totals[9]}</td>
            <td class="num"><fmt:formatNumber value="${totals[10]}" minFractionDigits="2" maxFractionDigits="2"/></td>
        </tr>
    </tfoot>
</table>
</div>

<%@ include file="common/footer.jspf" %>
