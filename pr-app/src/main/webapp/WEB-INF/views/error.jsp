<%@ include file="common/taglibs.jspf" %>
<c:set var="code" value="${not empty status ? status : requestScope['jakarta.servlet.error.status_code']}"/>
<c:set var="pageTitle" value="Error"/><c:set var="activeNav" value=""/>
<%@ include file="common/header.jspf" %>

<section class="card error-card">
    <h1>Error <c:out value="${code}"/></h1>
    <p><c:out value="${not empty message ? message : 'Something went wrong while processing your request.'}"/></p>
    <p><a class="btn btn-primary" href="${ctx}/">Back to Dashboard</a></p>
</section>

<%@ include file="common/footer.jspf" %>
