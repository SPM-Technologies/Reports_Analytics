<%@ include file="../common/taglibs.jspf" %>
<c:set var="pageTitle" value="Dropdown lists"/><c:set var="activeNav" value="settings"/><c:set var="settingsTab" value="lists"/>
<%@ include file="../common/header.jspf" %>

<h1>Settings</h1>
<%@ include file="subnav.jspf" %>

<p class="hint">One value per line, in the order shown on the form. Values you remove stay valid for patients who already have them.
    Genders, categories, visit types, priorities and payment modes are fixed because reports depend on them.</p>
<div class="grid">
<c:forEach var="l" items="${lists}">
    <section class="card span2" id="${l.key}">
        <h2><c:out value="${l.label}"/> <c:if test="${l.builtIn}"><span class="tag">built-in values</span></c:if></h2>
        <form method="post" action="${ctx}/settings/lists/${l.key}">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
            <textarea name="values" rows="6" style="width:100%"><c:out value="${l.getText()}"/></textarea>
            <small class="hint">At most ${l.maxLength} characters per value.
                <c:if test="${not empty l.inactive}">No longer offered:
                    <c:forEach var="v" items="${l.inactive}" varStatus="s"><c:out value="${v}"/>${s.last ? '' : ', '}</c:forEach></c:if></small>
            <div class="actions"><button type="submit" class="btn btn-primary">Save</button></div>
        </form>
    </section>
</c:forEach>
</div>

<%@ include file="../common/footer.jspf" %>
