<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="Downloads"/><c:set var="activeNav" value="settings"/><c:set var="settingsTab" value="downloads"/>
<%@ include file="../common/header.jspf" %>

<h1>Settings</h1>
<%@ include file="subnav.jspf" %>

<form method="post" action="${ctx}/settings/downloads" enctype="multipart/form-data">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <section class="card">
        <h2>Look of Excel and PDF downloads</h2>
        <div class="grid">
            <div class="f span2"><label>Title prefix</label>
                <input name="titlePrefix" value="${fn:escapeXml(branding.titlePrefix())}" maxlength="80" placeholder="e.g. City Hospital, Pune"></div>
            <div class="f span2"><label>Header line</label>
                <input name="headerText" value="${fn:escapeXml(branding.headerText())}" maxlength="200" placeholder="e.g. Confidential - for internal use"></div>
            <div class="f span2"><label>Footer line</label>
                <input name="footerText" value="${fn:escapeXml(branding.footerText())}" maxlength="200" placeholder="e.g. Medical Records Department"></div>
            <div class="f span2"><label>Logo (PNG or JPEG, at most 200 KB)</label>
                <input type="file" name="logo" accept="image/png,image/jpeg">
                <c:if test="${hasLogo}">
                    <img src="${ctx}/settings/downloads/logo" alt="Current logo" style="max-height:40px;margin-top:6px">
                    <label class="check"><input type="checkbox" name="removeLogo" value="true"> Remove the logo</label>
                </c:if></div>
        </div>
    </section>
    <section class="card">
        <h2>Formats users may download</h2>
        <p class="hint">Applies to users without the settings permission; administrators can always download every format.
            Who may download at all is decided by roles (permission "Download reports").</p>
        <c:forEach var="f" items="${formats}">
            <div class="f check"><label><input type="checkbox" name="formats" value="${f}" ${branding.allows(f) ? 'checked' : ''}>
                <c:out value="${f == 'xlsx' ? 'Excel' : f == 'pdf' ? 'PDF' : 'CSV'}"/></label></div>
        </c:forEach>
    </section>
    <div class="actions"><button type="submit" class="btn btn-primary">Save</button></div>
</form>

<%@ include file="../common/footer.jspf" %>
