package com.hospital.patientreg.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.patientreg.identity.model.AppUser;
import com.hospital.patientreg.shared.tenant.Permission;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Regression tests for the findings of the final third-eye review (16 Oct 2026): privilege escalation in user
 * administration, bulk copying without the download permission, legacy reports and field rules, the outbox and
 * template protections in the database, ClickHouse account limits, formulas, subtotal order and headers.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
        @ContextConfiguration(classes = RootConfig.class),
        @ContextConfiguration(classes = WebConfig.class)})
class FinalReviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate admin = TestUsers.adminJdbc();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private DataSource appDataSource;

    @BeforeAll
    static void setUp() throws Exception {
        TestUsers.ensureHospital2();
        AnalyticsTestData.seed();
    }

    @BeforeEach
    @AfterEach
    void reset() {
        admin.update("DELETE FROM reporting.field_setting WHERE hospital_id IN (1, 2)");
        admin.update("DELETE FROM reporting.export_setting WHERE hospital_id IN (1, 2)");
        admin.update("DELETE FROM identity.app_user_hospital WHERE role LIKE 'H1_FR_%'");
        admin.update("DELETE FROM identity.role WHERE role_key LIKE 'H1_FR_%'");
    }

    private MockMvc as(AppUser user) {
        return TestUsers.mvc(context, user);
    }

    private JsonNode query(AppUser user, String body, int status) throws Exception {
        String s = as(user).perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(s);
    }

    // ------------------------------------------------------------------ administration

    @Test
    void aUserManagerCannotHandOutRolesWithMorePermissionsThanTheirOwn() throws Exception {
        as(TestUsers.admin1()).perform(post("/admin/roles/new").param("hospitalId", "1").param("label", "FR User manager")
                .param("permissions", "MANAGE_USERS")).andExpect(status().is3xxRedirection());
        AppUser manager = TestUsers.withRole(9011, "test.fr.manager", 1, "H1_FR_USER_MANAGER", Set.of(Permission.MANAGE_USERS));
        for (String role : List.of("ADMIN", "REGISTRATION")) {
            as(manager).perform(post("/admin/users/new").param("username", "t.fr." + role.toLowerCase()).param("fullName", "X")
                    .param("password", "Secret123").param("roles[1]", role)).andExpect(status().isForbidden());
        }
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.app_user WHERE username LIKE 't.fr.%'", Long.class)).isZero();
        // only grantable roles are offered
        String page = as(manager).perform(get("/admin/users/new")).andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain("value=\"ADMIN\"");
    }

    @Test
    void aHospitalAdministratorCannotChangeASuperAdministratorsHospitals() throws Exception {
        Long superId = admin.queryForObject("SELECT min(user_id) FROM identity.app_user WHERE super_admin", Long.class);
        String before = admin.queryForObject("SELECT string_agg(hospital_id || ':' || role, ',' ORDER BY hospital_id) "
                + "FROM identity.app_user_hospital WHERE user_id = ?", String.class, superId);
        as(TestUsers.admin1()).perform(post("/admin/users/" + superId).param("fullName", "Hacked").param("active", "true")
                .param("roles[1]", "")).andExpect(status().is3xxRedirection());
        assertThat(admin.queryForObject("SELECT string_agg(hospital_id || ':' || role, ',' ORDER BY hospital_id) "
                + "FROM identity.app_user_hospital WHERE user_id = ?", String.class, superId)).isEqualTo(before);
        assertThat(admin.queryForObject("SELECT full_name FROM identity.app_user WHERE user_id = ?", String.class, superId))
                .isNotEqualTo("Hacked");
    }

    // ------------------------------------------------------------------ analytics

    @Test
    void withoutTheDownloadPermissionPatientListsCanBePagedOnlyToRow5000() throws Exception {
        AppUser viewer = TestUsers.withRole(9012, "test.fr.viewer", 1, "H1_FR_VIEWER", Set.of(Permission.ANALYTICS));
        String q = "{\"dataset\":\"patients\",\"columns\":[\"patient_name\"],\"pageSize\":50,\"offset\":%d}";
        query(viewer, q.formatted(4950), 200);
        JsonNode refused = query(viewer, q.formatted(4951), 400);
        assertThat(refused.get("detail").asText()).contains("5,000");
        // a summary is not limited, and with the permission neither is the list
        query(viewer, "{\"dataset\":\"patients\",\"columns\":[\"city\"],\"measures\":[{\"field\":\"patients\"}],\"offset\":0}", 200);
        query(TestUsers.reports1(), q.formatted(9000), 200);
    }

    @Test
    void internalFieldsCannotBeShownAndLabelsStayUnique() throws Exception {
        query(TestUsers.reports1(), "{\"dataset\":\"patients\",\"columns\":[\"patient_id\"]}", 400);
        query(TestUsers.reports1(), "{\"dataset\":\"patients\",\"columns\":[\"city\"],\"sort\":[{\"field\":\"patient_id\",\"dir\":\"asc\"}]}", 400);
        // a distinct count of patients is still allowed
        query(TestUsers.reports1(), "{\"dataset\":\"patients\",\"columns\":[],\"measures\":[{\"field\":\"patient_id\",\"agg\":\"countDistinct\"}]}", 200);
        // renaming Mobile to "Patient Name" would give two fields one name: refused, nothing saved
        as(TestUsers.admin1()).perform(post("/settings/report-fields").param("visibility_city", "HIDDEN")
                .param("visibility_mobile_masked", "NORMAL").param("label_mobile_masked", "Patient Name"))
                .andExpect(status().is3xxRedirection());
        assertThat(admin.queryForObject("SELECT count(*) FROM reporting.field_setting WHERE hospital_id = 1", Long.class)).isZero();
        // derived fields are not offered on the settings screen (they follow their source field)
        String page = as(TestUsers.admin1()).perform(get("/settings/report-fields")).andReturn().getModelAndView().getModel()
                .get("fields").toString();
        assertThat(page).doesNotContain("age_band").contains("age_years");
    }

    @Test
    void formulasAreStoredWithFieldNamesAndMixedNumbersWork() throws Exception {
        String r = as(TestUsers.reports1()).perform(post("/api/v1/formulas/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dataset\":\"patients\",\"formula\":\"[Age] + [fee (rs)]\",\"calculated\":[]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(r).get("formula").asText()).isEqualTo("[age_years] + [registration_fee]");
        // a Decimal fee and a 0.5 literal in IF: ClickHouse needs a common type
        query(TestUsers.reports1(), """
                {"dataset":"patients","columns":["calc_x"],"calculated":[{"name":"calc_x","label":"X","formula":"IF([Age] > 35, [Fee (Rs)], 0.5)"}]}""", 200);
        // whole years, not crossed calendar boundaries
        JsonNode y = query(TestUsers.reports1(), """
                {"dataset":"patients","columns":["calc_y"],"pageSize":1,"calculated":[{"name":"calc_y","label":"Y","formula":"YEARS_BETWEEN(DATE(\\"2000-12-31\\"), DATE(\\"2001-01-01\\"))"}]}""", 200);
        assertThat(y.get("rows").get(0).get(0).asInt()).isZero();
    }

    @Test
    void subtotalRowsComeAfterTheirGroup() throws Exception {
        JsonNode r = query(TestUsers.reports1(), """
                {"dataset":"patients","columns":["gender","city"],"measures":[{"field":"patients"}],"subtotals":true}""", 200);
        JsonNode rows = r.get("rows");
        int level = rows.get(0).size() - 1;
        assertThat(rows.get(0).get(level).asInt()).isZero();
        assertThat(rows.get(rows.size() - 1).get(level).asInt()).isEqualTo(1);
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).get(level).asInt() == 1) {   // a subtotal follows a detail row of the same group
                assertThat(rows.get(i - 1).get(0).asText()).isEqualTo(rows.get(i).get(0).asText());
            }
        }
    }

    @Test
    void theReportingAccountCannotLiftItsOwnLimits() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:clickhouse://localhost:8123/analytics_test", "reporting_app", "Report@1234");
             Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("SELECT 1 SETTINGS SQL_hospitals = '1', max_execution_time = 0"))
                    .hasMessageContaining("max_execution_time");
            assertThatThrownBy(() -> s.executeQuery("SELECT 1 SETTINGS SQL_hospitals = '1', max_memory_usage = 0"))
                    .hasMessageContaining("max_memory_usage");
        }
    }

    // ------------------------------------------------------------------ legacy reports

    @Test
    void theLegacyReportsFollowTheHospitalsFieldRules() throws Exception {
        as(TestUsers.admin1()).perform(WebSmokeTest.validRegistration(TestUsers.generalMedicine(1)).param("firstName", "Rulecheck"))
                .andExpect(status().is3xxRedirection());
        as(TestUsers.admin1()).perform(post("/settings/report-fields").param("visibility_city", "HIDDEN")
                .param("visibility_patient_name", "MASKED")).andExpect(status().is3xxRedirection());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) as(TestUsers.reports1()).perform(get("/reports/register"))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("rows");
        assertThat(rows).isNotEmpty().allSatisfy(r -> {
            assertThat(r[7]).isNull();                                       // City hidden
            assertThat(String.valueOf(r[3])).doesNotContain("Rulecheck");   // Patient Name masked
        });
        // the administrator of the hospital is subject to the same rules for HIDDEN fields
        @SuppressWarnings("unchecked")
        List<Object[]> adminRows = (List<Object[]>) as(TestUsers.admin1()).perform(get("/reports/register"))
                .andReturn().getModelAndView().getModel().get("rows");
        assertThat(adminRows).allSatisfy(r -> assertThat(r[7]).isNull());
    }

    @Test
    void aSwitchedOffFormatIsRefusedBeforeAnyDownloadHeader() throws Exception {
        as(TestUsers.admin1()).perform(post("/settings/downloads").param("formats", "xlsx")).andExpect(status().is3xxRedirection());
        as(TestUsers.reports1()).perform(get("/reports/register/export").param("format", "pdf"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Content-Disposition"));
        as(TestUsers.reports1()).perform(get("/reports/summary/export").param("format", "pdf"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Content-Disposition"));
    }

    // ------------------------------------------------------------------ database

    @Test
    void theApplicationCannotWriteTheOutboxOrTouchTemplates() {
        JdbcTemplate app = new JdbcTemplate(appDataSource);
        assertThatThrownBy(() -> app.update("INSERT INTO patientreg.report_outbox (aggregatetype, aggregateid, type, payload) "
                + "VALUES ('patient', '1', 'PatientSnapshot.v2', '{}')")).isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("permission denied");
        long templates = admin.queryForObject("SELECT count(*) FROM reporting.saved_report WHERE template_key IS NOT NULL", Long.class);
        assertThat(app.update("DELETE FROM reporting.saved_report WHERE template_key IS NOT NULL")).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM reporting.saved_report WHERE template_key IS NOT NULL", Long.class))
                .isEqualTo(templates);
    }

    @Test
    void aRenamedDepartmentIsCopiedAgainToTheReportingSide() throws Exception {
        int dept = TestUsers.generalMedicine(1);
        String location = as(TestUsers.admin1()).perform(WebSmokeTest.validRegistration(dept)).andReturn().getResponse().getRedirectedUrl();
        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        long before = admin.queryForObject("SELECT row_version FROM patientreg.patient WHERE patient_id = ?", Long.class, id);
        String name = admin.queryForObject("SELECT dept_name FROM patientreg.department WHERE dept_id = ?", String.class, dept);
        try {
            as(TestUsers.admin1()).perform(post("/settings/departments/" + dept).param("name", name + " X").param("active", "true"))
                    .andExpect(status().is3xxRedirection());
            assertThat(admin.queryForObject("SELECT row_version FROM patientreg.patient WHERE patient_id = ?", Long.class, id))
                    .isGreaterThan(before);
        } finally {
            admin.update("UPDATE patientreg.department SET dept_name = ? WHERE dept_id = ?", name, dept);
        }
    }

    // ------------------------------------------------------------------ headers

    @Test
    void pagesCarryAContentSecurityPolicyAndAReferrerPolicy() throws Exception {
        TestUsers.anonymous(context).perform(get("/login"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "same-origin"));
    }
}
