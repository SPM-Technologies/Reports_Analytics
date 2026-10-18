<%@ include file="common/taglibs.jspf" %>
<c:set var="pageTitle" value="Change password"/><c:set var="activeNav" value=""/>
<%@ include file="common/header.jspf" %>

<section class="card login-card">
    <h1>Change password</h1>
    <c:if test="${not empty error}"><div class="alert alert-error"><c:out value="${error}"/></div></c:if>
    <form method="post" action="${ctx}/account/password" autocomplete="off">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
        <div class="f"><label for="current">Current password</label>
            <input id="current" name="current" type="password" maxlength="72" required></div>
        <div class="f"><label for="password">New password</label>
            <input id="password" name="password" type="password" minlength="8" maxlength="72" required></div>
        <div class="f"><label for="confirm">Repeat new password</label>
            <input id="confirm" name="confirm" type="password" minlength="8" maxlength="72" required></div>
        <p class="hint">At least 8 characters, with a letter and a digit.</p>
        <button type="submit" class="btn btn-primary">Change password</button>
        <a class="btn" href="${ctx}/">Cancel</a>
    </form>
</section>

<%@ include file="common/footer.jspf" %>
