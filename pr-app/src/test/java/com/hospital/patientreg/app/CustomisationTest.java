package com.hospital.patientreg.app;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Stage 5b customisation (architecture section 10.2 / D14) through the real security chain: a hospital's own
 * form fields end to end, per-field report rules, shared calculated fields, dashboards, download settings,
 * custom roles, and the extra report options in downloads. Every setting stays inside its hospital.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
        @ContextConfiguration(classes = RootConfig.class),
        @ContextConfiguration(classes = WebConfig.class)})
class CustomisationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIELD_KEY = "t5b_ward";
    private final JdbcTemplate admin = TestUsers.adminJdbc();

    @Autowired
    private WebApplicationContext context;

    @BeforeAll
    static void setUp() throws Exception {
        TestUsers.ensureHospital2();
        AnalyticsTestData.seed();
        // patients 1 and 2 (H001) carry a value of the hospital's own field in ClickHouse; 5 (H002) a same-named key
        try (Connection c = DriverManager.getConnection("jdbc:clickhouse://localhost:8123/analytics_test", "admin",
                AnalyticsTestData.ADMIN_PASSWORD); Statement s = c.createStatement()) {
            s.execute("INSERT INTO patient_report SELECT * REPLACE (version + 10 AS version, "
                    + "map('" + FIELD_KEY + "', if(patient_id = 1, 'ICU', 'General')) AS custom_fields) "
                    + "FROM patient_report FINAL WHERE patient_id IN (1, 2, 5)");
        }
    }

    @BeforeEach
    @AfterEach
    void resetSettings() {
        admin.update("DELETE FROM reporting.field_setting WHERE hospital_id IN (1, 2)");
        admin.update("DELETE FROM reporting.calculated_field WHERE label LIKE 'T5b %'");
        admin.update("DELETE FROM reporting.dashboard WHERE name LIKE 'T5b %'");
        admin.update("DELETE FROM reporting.export_setting WHERE hospital_id IN (1, 2)");
        admin.update("DELETE FROM patientreg.custom_field_def WHERE field_key LIKE 't5b_%'");
        admin.update("DELETE FROM identity.role WHERE role_key LIKE 'H1_T5B%' OR role_key LIKE 'H2_T5B%'");
    }

    private MockMvc as(AppUser user) {
        return TestUsers.mvc(context, user);
    }

    private JsonNode json(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder request, int status) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? null : JSON.readTree(body);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder api(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode query(AppUser user, String body, int status) throws Exception {
        return json(as(user), api(post("/api/v1/query"), body), status);
    }

    private List<String> fieldNames(AppUser user) throws Exception {
        JsonNode ds = json(as(user), get("/api/v1/datasets"), 200).get(0);
        List<String> names = new ArrayList<>();
        ds.get("fields").forEach(f -> names.add(f.get("name").asText()));
        return names;
    }

    private void createWardField(boolean reportable) throws Exception {
        var request = post("/settings/fields/new").param("key", FIELD_KEY).param("label", "Ward").param("type", "DROPDOWN")
                .param("section", "additional").param("sortOrder", "1").param("_required", "on")
                .param("active", "true").param("_active", "on").param("options", "General\nICU\nPrivate")
                .param("_reportable", "on").param("sensitivity", "NORMAL");
        if (reportable) {
            request.param("reportable", "true");
        }
        as(TestUsers.admin1()).perform(request).andExpect(status().is3xxRedirection());
    }

    // ------------------------------------------------------------------ hospitals' own form fields

    @Test
    void anOwnFormFieldIsSavedWithThePatientAndBecomesAReportFieldOfThatHospitalOnly() throws Exception {
        createWardField(true);
        // the form shows it, validates it against its list, and saves it with the patient
        String form = as(TestUsers.admin1()).perform(get("/patient/new")).andReturn().getModelAndView().getModel()
                .get("customFields").toString();
        assertThat(form).contains(FIELD_KEY);
        as(TestUsers.admin1()).perform(WebSmokeTest.validRegistration(TestUsers.generalMedicine(1))
                        .param("customValues[" + FIELD_KEY + "]", "Penthouse"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model()
                        .attributeHasFieldErrors("patient", "customValues[" + FIELD_KEY + "]"));
        String location = as(TestUsers.admin1()).perform(WebSmokeTest.validRegistration(TestUsers.generalMedicine(1))
                .param("customValues[" + FIELD_KEY + "]", "ICU")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        assertThat(admin.queryForObject("SELECT custom_fields ->> ? FROM patientreg.patient WHERE patient_id = ?",
                String.class, FIELD_KEY, id)).isEqualTo("ICU");
        // the snapshot for the reporting side carries it too
        assertThat(admin.queryForObject("SELECT custom_fields ->> ? FROM patientreg.v_patient_report WHERE patient_id = ?",
                String.class, FIELD_KEY, id)).isEqualTo("ICU");

        // analytics: a field of H001 for H001 users, grouped and filtered like any other
        String cf = "cf_1_" + FIELD_KEY;
        assertThat(fieldNames(TestUsers.reports1())).contains(cf);
        JsonNode r = query(TestUsers.reports1(), """
                {"dataset":"patients","columns":["%s"],"measures":[{"field":"patients"}],"sort":[{"field":"%s","dir":"asc"}]}"""
                .formatted(cf, cf), 200);
        assertThat(r.get("rows").toString()).contains("[\"General\",1]", "[\"ICU\",1]");
        JsonNode f = query(TestUsers.reports1(), """
                {"dataset":"patients","columns":["patient_name"],"filter":{"field":"%s","op":"eq","value":"ICU"}}""".formatted(cf), 200);
        assertThat(f.get("rows").toString()).contains("Ravi Rao").doesNotContain("Amit Shah");
        // H002 has no such field; its patient's same-named key is never shown to H001
        assertThat(fieldNames(TestUsers.reports2())).doesNotContain(cf);
        query(TestUsers.reports2(), "{\"dataset\":\"patients\",\"columns\":[\"" + cf + "\"]}", 400);
        JsonNode group = query(TestUsers.group(), """
                {"dataset":"patients","columns":["patient_name","%s"]}""".formatted(cf), 200);
        assertThat(group.get("rows").toString()).contains("[\"Ms. Kavya Kulkarni\",\"\"]");
    }

    @Test
    void anOwnFieldThatIsNotReportableStaysOutOfReportsAndOutOfTheReportingCopy() throws Exception {
        createWardField(false);
        assertThat(fieldNames(TestUsers.reports1())).doesNotContain("cf_1_" + FIELD_KEY);
        String location = as(TestUsers.admin1()).perform(WebSmokeTest.validRegistration(TestUsers.generalMedicine(1))
                .param("customValues[" + FIELD_KEY + "]", "ICU")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        // stored with the patient, but not published to ClickHouse / Kafka (review finding)
        assertThat(admin.queryForObject("SELECT custom_fields ->> ? FROM patientreg.patient WHERE patient_id = ?",
                String.class, FIELD_KEY, id)).isEqualTo("ICU");
        assertThat(admin.queryForObject("SELECT custom_fields::text FROM patientreg.v_patient_report WHERE patient_id = ?",
                String.class, id)).isEqualTo("{}");
        // switching it on for reports re-publishes the patients holding a value (new version for the sync)
        long before = admin.queryForObject("SELECT row_version FROM patientreg.patient WHERE patient_id = ?", Long.class, id);
        as(TestUsers.admin1()).perform(post("/settings/fields/" + FIELD_KEY).param("label", "Ward").param("type", "DROPDOWN")
                .param("section", "additional").param("sortOrder", "1").param("_required", "on").param("active", "true")
                .param("_active", "on").param("options", "General\nICU\nPrivate").param("reportable", "true")
                .param("_reportable", "on").param("sensitivity", "NORMAL")).andExpect(status().is3xxRedirection());
        assertThat(admin.queryForObject("SELECT row_version FROM patientreg.patient WHERE patient_id = ?", Long.class, id))
                .isGreaterThan(before);
        assertThat(admin.queryForObject("SELECT custom_fields ->> ? FROM patientreg.v_patient_report WHERE patient_id = ?",
                String.class, FIELD_KEY, id)).isEqualTo("ICU");
    }

    @Test
    void maskingIsOnlyForFreeText() throws Exception {
        // a masked option would still give the value away ("PoXXXXve"): refused for list fields
        as(TestUsers.admin1()).perform(post("/settings/fields/new").param("key", FIELD_KEY).param("label", "Ward")
                        .param("type", "DROPDOWN").param("section", "additional").param("active", "true").param("_active", "on")
                        .param("options", "General\nICU").param("reportable", "true").param("_reportable", "on")
                        .param("sensitivity", "MASKED"))
                .andExpect(status().isOk());
        assertThat(admin.queryForObject("SELECT count(*) FROM patientreg.custom_field_def WHERE field_key = ?", Long.class,
                FIELD_KEY)).isZero();
        // a standard field with a short list of values is hidden instead of masked; its derived field follows
        as(TestUsers.admin1()).perform(post("/settings/report-fields").param("visibility_gender", "MASKED")
                .param("visibility_age_years", "HIDDEN")).andExpect(status().is3xxRedirection());
        assertThat(fieldNames(TestUsers.reports1())).doesNotContain("gender", "age_years", "age_band");
    }

    @Test
    void reportFieldSettingsAreSavedAllOrNothing() throws Exception {
        as(TestUsers.admin1()).perform(post("/settings/report-fields").param("visibility_city", "HIDDEN")
                .param("visibility_gender", "NORMAL").param("label_gender", "x".repeat(61))).andExpect(status().is3xxRedirection());
        assertThat(admin.queryForObject("SELECT count(*) FROM reporting.field_setting WHERE hospital_id = 1", Long.class)).isZero();
        assertThat(fieldNames(TestUsers.reports1())).contains("city");
    }

    @Test
    void aComputedTextIsNotAFixedDate() throws Exception {
        JsonNode bad = json(as(TestUsers.reports1()), api(post("/api/v1/formulas/validate"),
                "{\"dataset\":\"patients\",\"formula\":\"DATE(CONCAT(\\\"2024-01\\\", \\\"-01\\\"))\",\"calculated\":[]}"), 400);
        assertThat(bad.get("detail").asText()).contains("DATE needs a fixed date");
        JsonNode old = query(TestUsers.reports1(), """
                {"dataset":"patients","columns":["calc_d"],"pageSize":1,"calculated":[{"name":"calc_d","label":"D","formula":"DATE(\\"1950-05-01\\")"}]}""", 200);
        assertThat(old.get("rows").toString()).contains("1950-05-01");
    }

    @Test
    void onlyAHospitalsSettingsManagerMayChangeItsForm() throws Exception {
        as(TestUsers.reports1()).perform(get("/settings/fields")).andExpect(status().isForbidden());
        as(TestUsers.registration2()).perform(post("/settings/fields/new").param("key", "t5b_x").param("label", "X")
                .param("type", "TEXT")).andExpect(status().isForbidden());
        assertThat(admin.queryForObject("SELECT count(*) FROM patientreg.custom_field_def WHERE field_key = 't5b_x'",
                Long.class)).isZero();
    }

    // ------------------------------------------------------------------ report field rules

    @Test
    void fieldRulesHideMaskRenameAndKeepFieldsForAdministrators() throws Exception {
        as(TestUsers.reports1()).perform(post("/settings/report-fields").param("visibility_city", "HIDDEN"))
                .andExpect(status().isForbidden());
        as(TestUsers.admin1()).perform(post("/settings/report-fields")
                        .param("visibility_city", "HIDDEN")
                        .param("visibility_patient_name", "MASKED")
                        .param("visibility_payment_mode", "ADMIN_ONLY")
                        .param("visibility_gender", "NORMAL").param("label_gender", "Sex"))
                .andExpect(status().is3xxRedirection());

        List<String> names = fieldNames(TestUsers.reports1());
        assertThat(names).doesNotContain("city", "payment_mode").contains("patient_name", "gender");
        query(TestUsers.reports1(), "{\"dataset\":\"patients\",\"columns\":[\"city\"]}", 400);
        // a hidden field cannot be used through a formula either
        query(TestUsers.reports1(), """
                {"dataset":"patients","columns":["calc_c"],"calculated":[{"name":"calc_c","label":"C","formula":"[City]"}]}""", 400);
        JsonNode masked = query(TestUsers.reports1(), "{\"dataset\":\"patients\",\"columns\":[\"patient_name\",\"gender\"]}", 200);
        assertThat(masked.get("rows").toString()).doesNotContain("Ravi Rao").contains("MrXXXXXXXXao");
        assertThat(masked.get("columns").get(1).get("label").asText()).isEqualTo("Sex");
        // a masked field cannot be searched for its real value
        JsonNode search = query(TestUsers.reports1(), """
                {"dataset":"patients","columns":["patient_name"],"filter":{"field":"patient_name","op":"contains","value":"Ravi"}}""", 200);
        assertThat(search.get("totalRows").asLong()).isZero();
        // the administrator still sees the administrators-only field
        assertThat(fieldNames(TestUsers.admin1())).contains("payment_mode").doesNotContain("city");
        // another hospital's rules do not apply to H002
        assertThat(fieldNames(TestUsers.reports2())).contains("city", "payment_mode");
        JsonNode h2 = query(TestUsers.reports2(), "{\"dataset\":\"patients\",\"columns\":[\"patient_name\"]}", 200);
        assertThat(h2.get("rows").toString()).contains("Kavya Kulkarni");
        // a user of both hospitals: H001's rows hidden or masked, H002's shown
        JsonNode both = query(TestUsers.group(), """
                {"dataset":"patients","columns":["patient_name","city"],"sort":[{"field":"patient_name","dir":"asc"}]}""", 200);
        assertThat(both.get("rows").toString()).contains("[\"Ms. Kavya Kulkarni\",\"Nashik\"]").doesNotContain("Ravi Rao", "Pune");
    }

    // ------------------------------------------------------------------ shared calculated fields

    @Test
    void sharedCalculatedFieldsNeedThePermissionAndStayInTheirHospital() throws Exception {
        String body = """
                {"dataset":"patients","label":"T5b Fee band","formula":"IF([Fee (Rs)] >= 300, \\"High\\", \\"Low\\")","description":"test"}""";
        json(as(TestUsers.reports1()), api(post("/api/v1/calculated-fields"), body), 403);
        JsonNode f = json(as(TestUsers.admin1()), api(post("/api/v1/calculated-fields"), body), 201);
        String key = f.get("key").asText();
        assertThat(f.get("kind").asText()).isEqualTo("column");
        assertThat(fieldNames(TestUsers.reports1())).contains(key);
        assertThat(fieldNames(TestUsers.reports2())).doesNotContain(key);
        JsonNode r = query(TestUsers.reports1(), """
                {"dataset":"patients","columns":["%s"],"measures":[{"field":"patients"}],"sort":[{"field":"%s","dir":"asc"}]}"""
                .formatted(key, key), 200);
        assertThat(r.get("rows").toString()).isEqualTo("[[\"High\",2],[\"Low\",1]]");
        // a reports user may use it but not change it; another hospital cannot even see it
        json(as(TestUsers.reports1()), api(put("/api/v1/calculated-fields/" + f.get("id").asLong()),
                body.replace("}", ",\"active\":true}")), 403);
        json(as(TestUsers.reports2()), api(put("/api/v1/calculated-fields/" + f.get("id").asLong()),
                body.replace("}", ",\"active\":true}")), 404);
        // a broken formula is refused with its position
        JsonNode bad = json(as(TestUsers.admin1()), api(post("/api/v1/formulas/validate"),
                "{\"dataset\":\"patients\",\"formula\":\"ROUND([Age], \",\"calculated\":[]}"), 400);
        assertThat(bad.get("position").asInt()).isPositive();
    }

    // ------------------------------------------------------------------ dashboards

    @Test
    void dashboardsArePrivateUnlessSharedByAnAnalyst() throws Exception {
        String body = "{\"name\":\"T5b board\",\"visibility\":\"%s\",\"definition\":{\"tiles\":[{\"reportId\":1,\"width\":1}],"
                + "\"filters\":[{\"field\":\"gender\",\"op\":\"eq\",\"value\":\"Male\"}]}}";
        json(as(TestUsers.reports2()), api(post("/api/v1/dashboards"), body.formatted("HOSPITAL")), 403);
        // a tile must show a report the author may open
        json(as(TestUsers.reports2()), api(post("/api/v1/dashboards"),
                body.formatted("PRIVATE").replace("\"reportId\":1", "\"reportId\":99999999")), 400);
        JsonNode own = json(as(TestUsers.reports2()), api(post("/api/v1/dashboards"), body.formatted("PRIVATE")), 201);
        JsonNode shared = json(as(TestUsers.analyst2()), api(post("/api/v1/dashboards"), body.formatted("HOSPITAL")), 201);
        long ownId = own.get("id").asLong();
        long sharedId = shared.get("id").asLong();
        assertThat(shared.get("definition").get("filters").get(0).get("value").asText()).isEqualTo("Male");

        // a colleague sees the shared one only, and cannot change it
        json(as(TestUsers.group()), get("/api/v1/dashboards/" + ownId), 404);
        JsonNode seen = json(as(TestUsers.group()), get("/api/v1/dashboards/" + sharedId), 200);
        assertThat(seen.get("canEdit").asBoolean()).isFalse();
        json(as(TestUsers.group()), api(put("/api/v1/dashboards/" + sharedId), body.formatted("HOSPITAL")), 403);
        json(as(TestUsers.group()), delete("/api/v1/dashboards/" + sharedId), 403);
        // another hospital: nothing
        json(as(TestUsers.admin1()), get("/api/v1/dashboards/" + sharedId), 404);
        assertThat(json(as(TestUsers.admin1()), get("/api/v1/dashboards"), 200).toString()).doesNotContain("T5b board");
        // the authors may delete their own
        json(as(TestUsers.reports2()), delete("/api/v1/dashboards/" + ownId), 204);
        json(as(TestUsers.analyst2()), delete("/api/v1/dashboards/" + sharedId), 204);
    }

    // ------------------------------------------------------------------ downloads

    @Test
    void aHospitalMayLimitDownloadFormatsAndBrandItsFiles() throws Exception {
        as(TestUsers.admin1()).perform(post("/settings/downloads").param("formats", "xlsx")
                        .param("titlePrefix", "T5b Main Hospital").param("headerText", "Confidential").param("footerText", "Page"))
                .andExpect(status().is3xxRedirection());
        String csv = "{\"format\":\"csv\",\"query\":{\"dataset\":\"patients\",\"columns\":[\"patient_name\"]}}";
        String xlsx = "{\"format\":\"xlsx\",\"query\":{\"dataset\":\"patients\",\"columns\":[\"patient_name\"]}}";
        as(TestUsers.reports1()).perform(api(post("/api/v1/query/export"), csv)).andExpect(status().isForbidden());
        as(TestUsers.reports1()).perform(api(post("/api/v1/query/export"), xlsx)).andExpect(status().isOk());
        // the settings manager may still use every format
        as(TestUsers.admin1()).perform(api(post("/api/v1/query/export"), csv)).andExpect(status().isOk());
        // the legacy JSP downloads follow the same rule
        as(TestUsers.reports1()).perform(get("/reports/register/export").param("format", "pdf")).andExpect(status().isForbidden());
        // H002 is not affected
        as(TestUsers.reports2()).perform(api(post("/api/v1/query/export"), csv)).andExpect(status().isOk());
    }

    @Test
    void downloadsShowSubtotalsAndOthersLikeTheScreen() throws Exception {
        String sub = "{\"format\":\"csv\",\"query\":{\"dataset\":\"patients\",\"columns\":[\"gender\",\"city\"],"
                + "\"measures\":[{\"field\":\"patients\"}],\"subtotals\":true}}";
        String csv = as(TestUsers.reports1()).perform(api(post("/api/v1/query/export"), sub)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("Subtotal").doesNotContain("Subtotal level");
        String top = "{\"format\":\"csv\",\"query\":{\"dataset\":\"patients\",\"columns\":[\"city\"],"
                + "\"measures\":[{\"field\":\"patients\"}],\"topN\":1,\"sort\":[{\"field\":\"patients\",\"dir\":\"desc\"}]}}";
        String topCsv = as(TestUsers.reports1()).perform(api(post("/api/v1/query/export"), top)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(topCsv).contains("Pune", "Others").doesNotContain("Nagpur");
    }

    // ------------------------------------------------------------------ roles

    @Test
    void hospitalAdministratorsDefineTheirOwnRoles() throws Exception {
        as(TestUsers.admin1()).perform(post("/admin/roles/new").param("hospitalId", "1").param("label", "T5b Night desk")
                .param("permissions", "REGISTER", "VIEW_PATIENT")).andExpect(status().is3xxRedirection());
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.role WHERE role_key = 'H1_T5B_NIGHT_DESK'", Long.class))
                .isEqualTo(1);
        // not for a hospital the administrator does not manage, and not by a reports user
        as(TestUsers.admin1()).perform(post("/admin/roles/new").param("hospitalId", "2").param("label", "T5b Other")
                .param("permissions", "REGISTER")).andExpect(status().isForbidden());
        as(TestUsers.reports1()).perform(post("/admin/roles/new").param("hospitalId", "1").param("label", "T5b Mine")
                .param("permissions", "MANAGE_USERS")).andExpect(status().isForbidden());
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.role WHERE role_key IN ('H2_T5B_OTHER', 'H1_T5B_MINE')",
                Long.class)).isZero();
    }

    @Test
    void noAdministratorRaisesTheirOwnAccessThroughARole() throws Exception {
        // a user manager of H001 through a custom role
        as(TestUsers.admin1()).perform(post("/admin/roles/new").param("hospitalId", "1").param("label", "T5b User manager")
                .param("permissions", "MANAGE_USERS")).andExpect(status().is3xxRedirection());
        AppUser manager = TestUsers.withRole(9007, "test.usermgr1", 1, "H1_T5B_USER_MANAGER", java.util.Set.of(
                com.hospital.patientreg.shared.tenant.Permission.MANAGE_USERS));
        // cannot change the role they hold ...
        as(manager).perform(post("/admin/roles/H1_T5B_USER_MANAGER").param("label", "T5b User manager")
                .param("permissions", "MANAGE_USERS", "EXPORT")).andExpect(status().isForbidden());
        // ... nor create a role with permissions they do not have
        as(manager).perform(post("/admin/roles/new").param("hospitalId", "1").param("label", "T5b Exporter")
                .param("permissions", "EXPORT")).andExpect(status().isForbidden());
        assertThat(admin.queryForObject("SELECT array_to_string(permissions, ',') FROM identity.role "
                + "WHERE role_key = 'H1_T5B_USER_MANAGER'", String.class)).isEqualTo("MANAGE_USERS");
        assertThat(admin.queryForObject("SELECT count(*) FROM identity.role WHERE role_key = 'H1_T5B_EXPORTER'", Long.class)).isZero();
    }

    @Test
    void everyHospitalInADownloadMustAllowItsFormat() throws Exception {
        // H002 switches CSV off; a reports user of both hospitals, working in H001, cannot take H002's rows as CSV
        as(TestUsers.admin2()).perform(post("/settings/downloads").param("formats", "xlsx", "pdf"))
                .andExpect(status().is3xxRedirection());
        String csv = "{\"format\":\"csv\",\"query\":{\"dataset\":\"patients\",\"columns\":[\"patient_name\"]}}";
        as(TestUsers.group()).perform(api(post("/api/v1/query/export"), csv)).andExpect(status().isForbidden());
        as(TestUsers.reports1()).perform(api(post("/api/v1/query/export"), csv)).andExpect(status().isOk());
    }
}
