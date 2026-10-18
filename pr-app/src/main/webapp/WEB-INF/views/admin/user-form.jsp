<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="${empty existing ? 'New user' : 'Edit user'}"/><c:set var="activeNav" value="admin"/><c:set var="adminTab" value="users"/>
<%@ include file="../common/header.jspf" %>

<h1><c:out value="${empty existing ? 'New user' : 'Edit user '}"/><c:out value="${existing.username}"/></h1>
<%@ include file="subnav.jspf" %>

<form method="post" action="${ctx}/admin/users/${empty existing ? 'new' : existing.userId}" autocomplete="off">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <section class="card">
        <h2>Account</h2>
        <c:if test="${not canEditAccount}">
            <p class="hint">This user also works in hospitals you do not administer: you can change only their roles in your hospitals.</p>
        </c:if>
        <div class="grid">
            <div class="f"><label>Username <span class="req">*</span></label>
                <c:choose>
                    <c:when test="${empty existing}"><input name="username" value="${fn:escapeXml(user.username)}" maxlength="40" required
                        pattern="[a-z0-9][a-z0-9._\-]{2,39}" title="3-40 lower-case letters, digits, . _ -"></c:when>
                    <c:otherwise><input value="${fn:escapeXml(user.username)}" readonly></c:otherwise>
                </c:choose></div>
            <div class="f"><label>Full name <span class="req">*</span></label>
                <input name="fullName" value="${fn:escapeXml(user.fullName)}" maxlength="100" required ${canEditAccount ? '' : 'readonly'}></div>
            <c:if test="${canEditAccount}">
            <div class="f"><label>${empty existing ? 'Password <span class="req">*</span>' : 'New password (leave empty to keep)'}</label>
                <input name="password" type="password" minlength="8" maxlength="72" ${empty existing ? 'required' : ''}></div>
            <div class="f check"><label><input type="checkbox" name="active" value="true" ${user.active ? 'checked' : ''} ${self ? 'disabled' : ''}> Active</label>
                <input type="hidden" name="_active" value="on"></div>
            <c:if test="${tenant.superAdmin}">
                <div class="f check"><label><input type="checkbox" name="superAdmin" value="true" ${user.superAdmin ? 'checked' : ''} ${self ? 'disabled' : ''}>
                    Super administrator (manages hospitals and all users)</label><input type="hidden" name="_superAdmin" value="on"></div>
            </c:if>
            </c:if>
        </div>
        <p class="hint">Passwords: 8-72 characters with at least one letter and one digit.</p>
        <c:if test="${blockedNow}">
            <p class="alert alert-error">Sign-in for this user is blocked from at least one computer after too many wrong passwords.</p>
        </c:if>
    </section>

    <section class="card">
        <h2>Hospitals and roles</h2>
        <c:if test="${self}"><p class="hint">You cannot change your own roles.</p></c:if>
        <table class="data">
            <thead><tr><th>Hospital</th><th>Role</th></tr></thead>
            <tbody>
            <c:forEach var="h" items="${hospitals}">
                <tr>
                    <td><c:out value="${h.code} - ${h.name}"/><c:if test="${not h.active}"> <span class="tag warn">inactive</span></c:if></td>
                    <td><select name="roles[${h.hospitalId}]" ${self ? 'disabled' : ''}>
                        <option value="">No access</option>
                        <c:forEach var="r" items="${roles}">
                            <c:if test="${r.usableIn(h.hospitalId)}">
                                <option value="${r.key}" ${user.roles[h.hospitalId] == r.key ? 'selected' : ''}><c:out value="${r.label}"/><c:if test="${not r.builtin}"> (own role)</c:if></option>
                            </c:if>
                        </c:forEach>
                    </select></td>
                </tr>
            </c:forEach>
            </tbody>
        </table>
        <p class="hint">Built-in roles: Hospital administrator: everything, including users and settings. Registration desk: register and open patients,
            JSP reports. Reports and analytics: JSP reports, Analytics and downloads (mobile numbers masked). Analyst: as Reports, and may share
            reports, formulas and dashboards. Hospitals can add their own roles on the Roles screen.</p>
    </section>

    <div class="actions">
        <button type="submit" class="btn btn-primary">Save</button>
        <a class="btn" href="${ctx}/admin/users">Cancel</a>
    </div>
</form>
<c:if test="${blockedNow}">
    <form method="post" action="${ctx}/admin/users/${existing.userId}/unlock">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
        <button type="submit" class="btn">Unlock account</button>
    </form>
</c:if>

<%@ include file="../common/footer.jspf" %>
