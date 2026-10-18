<%@ include file="common/taglibs.jspf" %>
<c:set var="pageTitle" value="Sign in"/><c:set var="activeNav" value=""/>
<%@ include file="common/header.jspf" %>

<section class="card login-card">
    <h1>Sign in</h1>
    <c:if test="${not empty error}"><div class="alert alert-error"><c:out value="${error}"/></div></c:if>
    <c:if test="${not empty info}"><div class="alert alert-success"><c:out value="${info}"/></div></c:if>
    <form method="post" action="${ctx}/login" autocomplete="off">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
        <div class="f"><label for="username">Username</label>
            <input id="username" name="username" maxlength="40" required autofocus></div>
        <div class="f"><label for="password">Password</label>
            <input id="password" name="password" type="password" maxlength="72" required></div>
        <button type="submit" class="btn btn-primary">Sign in</button>
    </form>
    <p class="hint">Each user sees only the data of the hospitals they are assigned to.</p>
</section>

<%@ include file="common/footer.jspf" %>
