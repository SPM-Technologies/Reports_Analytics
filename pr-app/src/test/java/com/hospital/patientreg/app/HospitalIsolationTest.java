package com.hospital.patientreg.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.patientreg.identity.model.AppUser;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * Multi-hospital isolation (architecture section 10.1, success criterion S8): a user of one hospital tries
 * to reach another hospital's data by every path and gets nothing. Covers the live database (row-level
 * security, composite keys, per-hospital numbering), the JSP screens and reports, the analytics API,
 * saved reports, permissions, sign-in and the ClickHouse row filter.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
        @ContextConfiguration(classes = RootConfig.class),
        @ContextConfiguration(classes = WebConfig.class)})
class HospitalIsolationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate admin = TestUsers.adminJdbc();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private DataSource appDataSource;   // the application's pool (application role, row-level security)

    @BeforeAll
    static void setUp() throws Exception {
        TestUsers.ensureHospital2();
        AnalyticsTestData.seed();
    }

    private MockMvc as(AppUser user) {
        return TestUsers.mvc(context, user);
    }

    /** Registers a patient as {@code user} in their active hospital and returns the new patient id. */
    private long register(AppUser user, int deptId, String... extraParams) throws Exception {
        var request = WebSmokeTest.validRegistration(deptId);
        for (int i = 0; i + 1 < extraParams.length; i += 2) {
            request.param(extraParams[i], extraParams[i + 1]);
        }
        String location = as(user).perform(request).andExpect(status().is3xxRedirection()).andReturn()
                .getResponse().getRedirectedUrl();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    // ------------------------------------------------------------------ live database

    @Test
    void withoutASignedInUserTheApplicationSeesNoPatientData() {
        JdbcTemplate app = new JdbcTemplate(appDataSource);   // no request, so no hospitals
        assertThat(admin.queryForObject("SELECT count(*) FROM patient", Long.class)).isPositive();
        assertThat(app.queryForObject("SELECT count(*) FROM patient", Long.class)).isZero();
        assertThat(app.queryForObject("SELECT count(*) FROM department", Long.class)).isZero();
        assertThat(app.queryForObject("SELECT count(*) FROM patient_contact", Long.class)).isZero();
    }

    @Test
    void registrationGoesToTheUsersHospitalWithItsOwnNumberSeriesEvenIfTheFormSaysOtherwise() throws Exception {
        long id = register(TestUsers.registration2(), TestUsers.generalMedicine(2), "hospitalId", "1");   // forged field
        Map<String, Object> row = admin.queryForMap("SELECT hospital_id, registration_no FROM patient WHERE patient_id = ?", id);
        assertThat(row.get("hospital_id")).isEqualTo(2);
        assertThat((String) row.get("registration_no")).matches("H002-REG\\d{4}\\d{7}");
        for (String table : List.of("patient_contact", "patient_emergency_contact", "patient_insurance",
                "patient_medical_info", "patient_visit")) {
            assertThat(admin.queryForObject("SELECT hospital_id FROM " + table + " WHERE patient_id = ?", Integer.class, id))
                    .as(table).isEqualTo(2);
        }
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.audit_log WHERE action = 'PATIENT_REGISTERED' "
                + "AND hospital_id = 2 AND detail = ?", Long.class, row.get("registration_no"))).isEqualTo(1);
    }

    @Test
    void numbersArePerHospitalAndConsecutive() throws Exception {
        long a = register(TestUsers.registration2(), TestUsers.generalMedicine(2));
        long b = register(TestUsers.registration2(), TestUsers.generalMedicine(2));
        String first = admin.queryForObject("SELECT registration_no FROM patient WHERE patient_id = ?", String.class, a);
        String second = admin.queryForObject("SELECT registration_no FROM patient WHERE patient_id = ?", String.class, b);
        assertThat(Long.parseLong(second.substring(12)) - Long.parseLong(first.substring(12))).isEqualTo(1);
    }

    @Test
    void anotherHospitalsDepartmentIsRejected() throws Exception {
        as(TestUsers.registration2()).perform(WebSmokeTest.validRegistration(TestUsers.generalMedicine(1)))
                .andExpect(status().isOk())   // the form comes back with an error, nothing is saved
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model()
                        .attributeHasFieldErrors("patient", "deptId"));
    }

    @Test
    void theDatabaseItselfRejectsAMixedRecord() throws Exception {
        long id = register(TestUsers.registration2(), TestUsers.generalMedicine(2));
        assertThatThrownBy(() -> admin.update("UPDATE patient_contact SET hospital_id = 1 WHERE patient_id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> admin.update("UPDATE patient_visit SET dept_id = ? WHERE patient_id = ?",
                TestUsers.generalMedicine(1), id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Review finding: department ids above 127 were compared as objects and always rejected. */
    @Test
    void departmentsWithLargeIdsAreAccepted() throws Exception {
        admin.update("INSERT INTO department (dept_id, hospital_id, dept_code, dept_name) VALUES (5000, 2, 'BIG', 'Big Id Dept') "
                + "ON CONFLICT DO NOTHING");
        long id = register(TestUsers.registration2(), 5000);
        assertThat(admin.queryForObject("SELECT dept_id FROM patient_visit WHERE patient_id = ?", Integer.class, id)).isEqualTo(5000);
    }

    /** Review finding: the application role must not read the owner-rights view, which has no row-level security. */
    @Test
    void theApplicationCannotReadThePublishedViewDirectly() {
        JdbcTemplate app = new JdbcTemplate(appDataSource);
        assertThatThrownBy(() -> app.queryForObject("SELECT count(*) FROM v_patient_report", Long.class))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void downloadsNeedTheExportPermissionAndMaskedUsersCannotSearchByMobile() throws Exception {
        as(TestUsers.registration2()).perform(get("/reports/register/export").param("format", "excel")).andExpect(status().isForbidden());
        as(TestUsers.registration2()).perform(get("/reports/summary/export").param("format", "pdf")).andExpect(status().isForbidden());
        register(TestUsers.admin1(), 1);   // mobile 9000000001 in H001
        String withMobile = as(TestUsers.admin1()).perform(get("/reports/register").param("keyword", "9000000001"))
                .andReturn().getModelAndView().getModel().get("total").toString();
        String masked = as(TestUsers.reports1()).perform(get("/reports/register").param("keyword", "9000000001"))
                .andReturn().getModelAndView().getModel().get("total").toString();
        assertThat(Long.parseLong(withMobile)).isPositive();
        assertThat(masked).isEqualTo("0");
    }

    // ------------------------------------------------------------------ JSP screens and reports

    @Test
    void anotherHospitalsPatientIsNotFound() throws Exception {
        long id = register(TestUsers.registration2(), TestUsers.generalMedicine(2));
        as(TestUsers.registration2()).perform(get("/patient/" + id)).andExpect(status().isOk());
        as(TestUsers.admin1()).perform(get("/patient/" + id)).andExpect(status().isNotFound());
        as(TestUsers.group()).perform(get("/patient/" + id)).andExpect(status().isNotFound());   // reports role: no patient records
    }

    @Test
    void legacyReportsAndDashboardShowOnlyTheActiveHospital() throws Exception {
        long id = register(TestUsers.registration2(), TestUsers.generalMedicine(2));
        String regNo = admin.queryForObject("SELECT registration_no FROM patient WHERE patient_id = ?", String.class, id);
        String h2 = as(TestUsers.registration2()).perform(get("/reports/register").param("keyword", regNo))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("total").toString();
        String h1 = as(TestUsers.admin1()).perform(get("/reports/register").param("keyword", regNo))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("total").toString();
        assertThat(h2).isEqualTo("1");
        assertThat(h1).isEqualTo("0");
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) as(TestUsers.registration2()).perform(get("/"))
                .andReturn().getModelAndView().getModel().get("counts");
        long h2Patients = admin.queryForObject("SELECT count(*) FROM patient WHERE hospital_id = 2", Long.class);
        assertThat(((Number) counts.get("total")).longValue()).isEqualTo(h2Patients);
    }

    @Test
    void reportsOnlyUsersSeeMaskedMobilesAndNoPatientLinks() throws Exception {
        register(TestUsers.admin1(), 1);
        MvcResult r = as(TestUsers.reports1()).perform(get("/reports/register")).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) r.getModelAndView().getModel().get("rows");
        assertThat(rows).isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(row[6])).matches("\\d{2}XXXXXX\\d{2}"));
        assertThat(r.getModelAndView().getModel().get("canViewPatient")).isEqualTo(false);
    }

    @Test
    void permissionsAreCheckedPerHospital() throws Exception {
        as(TestUsers.reports2()).perform(get("/patient/new")).andExpect(status().isForbidden());
        as(TestUsers.reports2()).perform(WebSmokeTest.validRegistration()).andExpect(status().isForbidden());
        as(TestUsers.registration2()).perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dataset\":\"patients\",\"columns\":[\"gender\"]}")).andExpect(status().isForbidden());
        as(TestUsers.registration2()).perform(get("/admin/users")).andExpect(status().isForbidden());
        as(TestUsers.reports2()).perform(get("/admin/hospitals")).andExpect(status().isForbidden());
        // switching to a hospital the user is not assigned to is refused
        as(TestUsers.reports2()).perform(post("/session/hospital").param("hospitalId", "1")).andExpect(status().isForbidden());
    }

    @Test
    void nobodySignedInMeansSignInPageOrUnauthorized() throws Exception {
        MockMvc anonymous = TestUsers.anonymous(context);
        anonymous.perform(get("/")).andExpect(status().is3xxRedirection());
        anonymous.perform(get("/patient/1")).andExpect(status().is3xxRedirection());
        anonymous.perform(get("/api/v1/datasets")).andExpect(status().isUnauthorized());
        anonymous.perform(get("/login")).andExpect(status().isOk());
        // a change without the CSRF token is refused even for a signed-in user
        AppUser user = TestUsers.admin1();
        anonymous.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                        org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                                user, null, user.getAuthorities()))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ analytics

    private JsonNode query(AppUser user, String body) throws Exception {
        return JSON.readTree(as(user).perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static final String TOTALS = """
            {"dataset":"patients","columns":[],"measures":[{"field":"patients"},{"field":"registration_fee","agg":"sum"}]}""";

    @Test
    void analyticsCoverOnlyTheUsersHospitals() throws Exception {
        assertThat(query(TestUsers.admin1(), TOTALS).get("totals").get(0).asLong()).isEqualTo(3);
        assertThat(query(TestUsers.reports2(), TOTALS).get("totals").get(0).asLong()).isEqualTo(2);
        assertThat(query(TestUsers.reports2(), TOTALS).get("totals").get(1).decimalValue()).isEqualByComparingTo("1500");
        assertThat(query(TestUsers.group(), TOTALS).get("totals").get(0).asLong()).isEqualTo(5);
        // asking for the other hospital explicitly only narrows: nothing
        JsonNode other = query(TestUsers.reports2(), """
                {"dataset":"patients","columns":["registration_no"],
                 "filter":{"field":"hospital_code","op":"eq","value":"H001"}}""");
        assertThat(other.get("totalRows").asLong()).isZero();
        // "data as of" also only looks at the user's hospitals
        assertThat(query(TestUsers.admin1(), TOTALS).get("dataAsOf").asText()).startsWith("2026-10-06");
        assertThat(query(TestUsers.reports2(), TOTALS).get("dataAsOf").asText()).startsWith("2026-10-07");
    }

    @Test
    void groupUsersCanCompareHospitals() throws Exception {
        JsonNode r = query(TestUsers.group(), """
                {"dataset":"patients","columns":["hospital_code"],"measures":[{"field":"patients"}],
                 "sort":[{"field":"hospital_code","dir":"asc"}]}""");
        assertThat(r.get("rows").toString()).isEqualTo("[[\"H001\",3],[\"H002\",2]]");
        JsonNode fields = JSON.readTree(as(TestUsers.group()).perform(get("/api/v1/datasets/patients"))
                .andReturn().getResponse().getContentAsString()).get("fields");
        assertThat(fields.toString()).contains("\"hospital_name\"");
        JsonNode single = JSON.readTree(as(TestUsers.admin1()).perform(get("/api/v1/datasets/patients"))
                .andReturn().getResponse().getContentAsString()).get("fields");
        assertThat(single.toString()).doesNotContain("\"hospital_name\"");
    }

    @Test
    void suggestionsAndDownloadsAreLimitedToo() throws Exception {
        String cities = as(TestUsers.admin1()).perform(get("/api/v1/datasets/patients/fields/city/values"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(cities).contains("Pune").doesNotContain("Nashik", "Kolhapur");
        String csv = new String(as(TestUsers.reports2()).perform(post("/api/v1/query/export").contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"csv\",\"query\":{\"dataset\":\"patients\",\"columns\":[\"patient_name\",\"city\"]}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Kavya Kulkarni", "Rohan Pawar").doesNotContain("Ravi Rao", "Neha Joshi");
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.audit_log WHERE action = 'REPORT_EXPORTED' "
                + "AND username = 'test.reports2'", Long.class)).isPositive();
    }

    @Test
    void savedReportsBelongToAHospital() throws Exception {
        String body = """
                {"name":"H2 only","definition":{"query":{"dataset":"patients","columns":["gender"],"measures":[{"field":"patients"}]}}}""";
        // a reports user may keep reports only for themself: private by default, sharing is refused
        as(TestUsers.reports2()).perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"definition\"", "\"visibility\":\"HOSPITAL\",\"definition\""))).andExpect(status().isForbidden());
        JsonNode own = JSON.readTree(as(TestUsers.reports2()).perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long privateId = own.get("id").asLong();
        assertThat(own.get("visibility").asText()).isEqualTo("PRIVATE");
        try {
            as(TestUsers.group()).perform(get("/api/v1/reports/" + privateId)).andExpect(status().isNotFound());
        } finally {
            as(TestUsers.reports2()).perform(delete("/api/v1/reports/" + privateId)).andExpect(status().isNoContent());
        }

        // an analyst shares with the hospital (the default for users who may share)
        JsonNode saved = JSON.readTree(as(TestUsers.analyst2()).perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = saved.get("id").asLong();
        assertThat(saved.get("canEdit").asBoolean()).isTrue();
        assertThat(saved.get("visibility").asText()).isEqualTo("HOSPITAL");
        assertThat(saved.get("hospital").asText()).isEqualTo("H002 Test Hospital Two");
        try {
            // another hospital: not listed, not found, cannot be changed
            as(TestUsers.admin1()).perform(get("/api/v1/reports/" + id)).andExpect(status().isNotFound());
            assertThat(as(TestUsers.admin1()).perform(get("/api/v1/reports")).andReturn().getResponse().getContentAsString())
                    .doesNotContain("H2 only");
            as(TestUsers.admin1()).perform(delete("/api/v1/reports/" + id)).andExpect(status().isNotFound());
            // same hospital, other user: may open and run it, but not change it
            JsonNode shared = JSON.readTree(as(TestUsers.group()).perform(get("/api/v1/reports/" + id))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(shared.get("canEdit").asBoolean()).isFalse();
            as(TestUsers.group()).perform(put("/api/v1/reports/" + id).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            as(TestUsers.group()).perform(delete("/api/v1/reports/" + id)).andExpect(status().isForbidden());
        } finally {
            as(TestUsers.analyst2()).perform(delete("/api/v1/reports/" + id)).andExpect(status().isNoContent());
        }
    }

    /** Second line of defence: the reporting account cannot read rows without naming the hospitals. */
    @Test
    void clickHouseRowFilterBlocksQueriesWithoutTheHospitalList() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:clickhouse://localhost:8123/analytics_test", "reporting_app", "Report@1234");
             Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("SELECT count() FROM patient_report")).hasMessageContaining("SQL_hospitals");
            // only the report table and the heartbeat are granted (review finding: the pipeline views had no filter)
            assertThatThrownBy(() -> s.executeQuery("SELECT count() FROM analytics.mv_patient_report_sync")).hasMessageContaining("ACCESS_DENIED");
            assertThatThrownBy(() -> s.executeQuery("SELECT count() FROM system.query_log")).hasMessageContaining("ACCESS_DENIED");
            try (ResultSet rs = s.executeQuery("SELECT count() FROM patient_report SETTINGS SQL_hospitals = '2'")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2);
            }
        }
    }
}
