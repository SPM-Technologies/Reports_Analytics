package com.hospital.patientreg.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Edge cases of the reporting API against the real ClickHouse / PostgreSQL test databases:
 * every operator and type, boundaries, invalid input, empty results, Unicode, saved-report
 * validation and download limits. Seed data: see {@link AnalyticsTestData}.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
        @ContextConfiguration(classes = RootConfig.class),
        @ContextConfiguration(classes = WebConfig.class)})
class ReportingEdgeCaseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeAll
    static void seed() throws Exception {
        AnalyticsTestData.seed();
    }

    @BeforeEach
    void setUp() {
        mvc = TestUsers.mvc(context, TestUsers.admin1());
    }

    // ------------------------------------------------------------------ helpers

    private static String detail(String filter) {
        return "{\"dataset\":\"patients\",\"columns\":[\"registration_no\"],\"filter\":" + filter + "}";
    }

    private JsonNode ok(String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private String bad(String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(r.getResponse().getContentType()).contains("problem+json");
        return JSON.readTree(r.getResponse().getContentAsString()).get("detail").asText();
    }

    private long count(String filter) throws Exception {
        return ok(detail(filter)).get("totalRows").asLong();
    }

    // ------------------------------------------------------------------ every operator returns the right rows

    static Stream<Arguments> operators() {
        return Stream.of(
                Arguments.of("{\"field\":\"gender\",\"op\":\"eq\",\"value\":\"Male\"}", 2),
                Arguments.of("{\"field\":\"gender\",\"op\":\"neq\",\"value\":\"Male\"}", 1),
                Arguments.of("{\"field\":\"city\",\"op\":\"in\",\"value\":[\"Pune\",\"Nagpur\"]}", 3),
                Arguments.of("{\"field\":\"city\",\"op\":\"notIn\",\"value\":[\"Pune\"]}", 1),
                Arguments.of("{\"field\":\"patient_name\",\"op\":\"contains\",\"value\":\"RAO\"}", 1),
                Arguments.of("{\"field\":\"patient_name\",\"op\":\"startsWith\",\"value\":\"mr.\"}", 2),
                Arguments.of("{\"field\":\"age_years\",\"op\":\"gt\",\"value\":30}", 1),
                Arguments.of("{\"field\":\"age_years\",\"op\":\"gte\",\"value\":\"30\"}", 2),
                Arguments.of("{\"field\":\"age_years\",\"op\":\"lt\",\"value\":30}", 1),
                Arguments.of("{\"field\":\"age_years\",\"op\":\"lte\",\"value\":40}", 3),
                Arguments.of("{\"field\":\"age_years\",\"op\":\"between\",\"value\":[25,30]}", 2),
                Arguments.of("{\"field\":\"registration_fee\",\"op\":\"gte\",\"value\":\"250.00\"}", 3),
                Arguments.of("{\"field\":\"registration_date\",\"op\":\"between\",\"value\":[\"2026-10-02\",\"2026-10-03\"]}", 2),
                Arguments.of("{\"field\":\"registration_date\",\"op\":\"eq\",\"value\":\"2026-10-01\"}", 1),
                Arguments.of("{\"field\":\"registration_month\",\"op\":\"eq\",\"value\":\"2026-10-01\"}", 3),
                Arguments.of("{\"field\":\"is_insured\",\"op\":\"isTrue\"}", 2),
                Arguments.of("{\"field\":\"is_insured\",\"op\":\"isFalse\"}", 1),
                Arguments.of("{\"or\":[{\"field\":\"city\",\"op\":\"eq\",\"value\":\"Nagpur\"},{\"field\":\"age_years\",\"op\":\"gte\",\"value\":40}]}", 2),
                Arguments.of("{\"and\":[{\"or\":[{\"field\":\"gender\",\"op\":\"eq\",\"value\":\"Male\"}]},{\"and\":[{\"field\":\"city\",\"op\":\"eq\",\"value\":\"Pune\"}]}]}", 2),
                // nothing matches: empty, not an error
                Arguments.of("{\"field\":\"city\",\"op\":\"eq\",\"value\":\"Atlantis\"}", 0),
                // wildcards typed by users match literally
                Arguments.of("{\"field\":\"patient_name\",\"op\":\"contains\",\"value\":\"%\"}", 0),
                Arguments.of("{\"field\":\"patient_name\",\"op\":\"contains\",\"value\":\"_\"}", 0),
                // Unicode search runs fine (and finds nothing in this data)
                Arguments.of("{\"field\":\"patient_name\",\"op\":\"contains\",\"value\":\"राम\"}", 0),
                // the deleted patient (Delhi) never appears
                Arguments.of("{\"field\":\"city\",\"op\":\"eq\",\"value\":\"Delhi\"}", 0),
                // only the newest version of patient 3 counts (Mumbai was version 1)
                Arguments.of("{\"field\":\"city\",\"op\":\"eq\",\"value\":\"Mumbai\"}", 0));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("operators")
    void operatorsReturnTheRightRows(String filter, long expected) throws Exception {
        assertThat(count(filter)).isEqualTo(expected);
    }

    @Test
    void relativePeriodsAllRun() throws Exception {
        for (String p : new String[] {"TODAY", "YESTERDAY", "LAST_7_DAYS", "LAST_30_DAYS", "THIS_MONTH", "LAST_MONTH", "THIS_YEAR", "LAST_YEAR"}) {
            ok(detail("{\"field\":\"registration_date\",\"op\":\"relative\",\"value\":\"" + p + "\"}"));
        }
    }

    // ------------------------------------------------------------------ invalid input is rejected with a clear message

    static Stream<Arguments> invalid() {
        String many = IntStream.range(0, 1001).mapToObj(i -> "\"c" + i + "\"").collect(Collectors.joining(","));
        String deep = "{\"field\":\"gender\",\"op\":\"eq\",\"value\":\"Male\"}";
        for (int i = 0; i < 4; i++) {
            deep = "{\"and\":[" + deep + "]}";
        }
        String fiftyOne = IntStream.range(0, 51).mapToObj(i -> "{\"field\":\"gender\",\"op\":\"eq\",\"value\":\"Male\"}")
                .collect(Collectors.joining(","));
        return Stream.of(
                Arguments.of("{\"dataset\":\"nope\",\"columns\":[\"gender\"]}", "Unknown dataset"),
                Arguments.of("{\"columns\":[\"gender\"]}", "Unknown dataset"),
                Arguments.of("{\"dataset\":\"patients\",\"columns\":[]}", "at least one column"),
                Arguments.of("{\"dataset\":\"patients\",\"columns\":[\"gender\",\"gender\"]}", "listed twice"),
                Arguments.of("{\"dataset\":\"patients\",\"columns\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"],\"measures\":[{\"field\":\"patients\"}]}", "group-by"),
                Arguments.of("{\"dataset\":\"patients\",\"columns\":[\"gender\"],\"measures\":[{\"field\":\"patients\"},{\"field\":\"patients\"}]}", "listed twice"),
                Arguments.of("{\"dataset\":\"patients\",\"columns\":[\"gender\"],\"measures\":[{\"field\":\"registration_fee\"}]}", "not allowed"),
                Arguments.of("{\"dataset\":\"patients\",\"columns\":[\"gender\"],\"pageSize\":0}", "Page size"),
                Arguments.of("{\"dataset\":\"patients\",\"columns\":[\"gender\"],\"pageSize\":501}", "Page size"),
                Arguments.of("{\"dataset\":\"patients\",\"columns\":[\"gender\"],\"offset\":-1}", "Offset"),
                Arguments.of("{\"dataset\":\"patients\",\"columns\":[\"gender\"],\"sort\":[{\"field\":\"gender\",\"dir\":\"up\"}]}", "asc or desc"),
                Arguments.of("{\"dataset\":\"patients\",\"columns\":[\"gender\"],\"measures\":[{\"field\":\"patients\"}],\"sort\":[{\"field\":\"city\",\"dir\":\"asc\"}]}", "Cannot sort"),
                Arguments.of(detail("{\"field\":\"age_years\",\"op\":\"gt\",\"value\":\"abc\"}"), "valid number"),
                Arguments.of(detail("{\"field\":\"registration_date\",\"op\":\"eq\",\"value\":\"2026-13-45\"}"), "YYYY-MM-DD"),
                Arguments.of(detail("{\"field\":\"gender\",\"op\":\"eq\",\"value\":[\"Male\"]}"), "single value"),
                Arguments.of(detail("{\"field\":\"gender\",\"op\":\"eq\"}"), "single value"),
                Arguments.of(detail("{\"field\":\"gender\",\"op\":\"in\",\"value\":[]}"), "1 to 1000"),
                Arguments.of(detail("{\"field\":\"gender\",\"op\":\"in\",\"value\":[" + many + "]}"), "1 to 1000"),
                Arguments.of(detail("{\"field\":\"gender\",\"op\":\"in\",\"value\":\"Male\"}"), "list of values"),
                Arguments.of(detail("{\"field\":\"age_years\",\"op\":\"between\",\"value\":[1]}"), "exactly two"),
                Arguments.of(detail("{\"field\":\"registration_date\",\"op\":\"relative\",\"value\":\"NEXT_DECADE\"}"), "relative period"),
                Arguments.of(detail("{\"field\":\"is_insured\",\"op\":\"eq\",\"value\":\"1\"}"), "not allowed"),
                Arguments.of(detail("{\"field\":\"patient_name\",\"op\":\"contains\",\"value\":\"   \"}"), "Enter text"),
                Arguments.of(detail("{\"field\":\"patient_name\",\"op\":\"contains\",\"value\":\"" + "x".repeat(201) + "\"}"), "too long"),
                Arguments.of(detail("{\"field\":\"patients\",\"op\":\"eq\",\"value\":1}"), "is a measure"),
                Arguments.of(detail("{\"field\":\"aadhaar\",\"op\":\"eq\",\"value\":\"1\"}"), "Unknown field"),
                Arguments.of(detail("{\"and\":[{\"field\":\"gender\",\"op\":\"eq\",\"value\":\"Male\"}],\"or\":[{\"field\":\"gender\",\"op\":\"eq\",\"value\":\"Male\"}]}"), "only 'and' or only 'or'"),
                Arguments.of(detail("{\"and\":[" + deep + "]}"), "nested too deeply"),
                Arguments.of(detail("{\"and\":[" + fiftyOne + "]}"), "At most 50"));
    }

    @ParameterizedTest(name = "[{index}] -> {1}")
    @MethodSource("invalid")
    void invalidDefinitionsAreRejected(String body, String messagePart) throws Exception {
        assertThat(bad(body)).containsIgnoringCase(messagePart);
    }

    @Test
    void depthFourIsStillAllowed() throws Exception {
        String deep = "{\"field\":\"gender\",\"op\":\"eq\",\"value\":\"Male\"}";
        for (int i = 0; i < 3; i++) {
            deep = "{\"and\":[" + deep + "]}";
        }
        assertThat(count(deep)).isEqualTo(2);
    }

    @Test
    void malformedBodiesAreRejected() throws Exception {
        for (String body : new String[] {"", "not json", "[]", "{\"dataset\":\"patients\",\"columns\":\"gender\"}"}) {
            mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        // unknown properties are ignored rather than failing
        ok("{\"dataset\":\"patients\",\"columns\":[\"gender\"],\"somethingNew\":true}");
    }

    // ------------------------------------------------------------------ boundaries and shapes

    @Test
    void paging() throws Exception {
        assertThat(ok("{\"dataset\":\"patients\",\"columns\":[\"registration_no\"],\"pageSize\":1}").get("rows")).hasSize(1);
        ok("{\"dataset\":\"patients\",\"columns\":[\"registration_no\"],\"pageSize\":500}");
        JsonNode beyond = ok("{\"dataset\":\"patients\",\"columns\":[\"registration_no\"],\"pageSize\":2,\"offset\":100}");
        assertThat(beyond.get("rows")).isEmpty();
        assertThat(beyond.get("totalRows").asLong()).isEqualTo(3);
        // pages never overlap and cover every row once
        String p1 = ok("{\"dataset\":\"patients\",\"columns\":[\"registration_no\"],\"pageSize\":2,\"offset\":0}").get("rows").toString();
        String p2 = ok("{\"dataset\":\"patients\",\"columns\":[\"registration_no\"],\"pageSize\":2,\"offset\":2}").get("rows").toString();
        assertThat(p1 + p2).contains("REG20260000001", "REG20260000002", "REG20260000003");
    }

    @Test
    void emptyResultsHaveNoNaN() throws Exception {
        String body = "{\"dataset\":\"patients\",\"columns\":[],\"measures\":[{\"field\":\"patients\"},{\"field\":\"age_years\",\"agg\":\"avg\"},"
                + "{\"field\":\"registration_fee\",\"agg\":\"sum\"}],\"filter\":{\"field\":\"city\",\"op\":\"eq\",\"value\":\"Atlantis\"}}";
        String raw = mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContainIgnoringCase("nan").doesNotContain("Infinity");
        JsonNode r = JSON.readTree(raw);
        assertThat(r.get("rows").get(0).get(0).asLong()).isZero();
        assertThat(r.get("rows").get(0).get(1).isNull()).isTrue();
    }

    @Test
    void measuresWithoutGroupsGiveOneRow() throws Exception {
        JsonNode r = ok("{\"dataset\":\"patients\",\"columns\":[],\"measures\":[{\"field\":\"patients\"},{\"field\":\"patient_id\",\"agg\":\"countDistinct\"},{\"field\":\"is_insured\",\"agg\":\"sum\"}]}");
        assertThat(r.get("rows").toString()).isEqualTo("[[3,3,2]]");
        assertThat(r.get("totalRows").asLong()).isEqualTo(1);
    }

    @Test
    void sortByMeasureAndByMonth() throws Exception {
        JsonNode r = ok("{\"dataset\":\"patients\",\"columns\":[\"city\"],\"measures\":[{\"field\":\"registration_fee\",\"agg\":\"sum\"}],"
                + "\"sort\":[{\"field\":\"sum_registration_fee\",\"dir\":\"asc\"}]}");
        assertThat(r.get("rows").get(0).get(0).asText()).isEqualTo("Nagpur");
        JsonNode m = ok("{\"dataset\":\"patients\",\"columns\":[\"registration_month\"],\"measures\":[{\"field\":\"patients\"}],"
                + "\"sort\":[{\"field\":\"registration_month\",\"dir\":\"asc\"}]}");
        assertThat(m.get("rows").toString()).isEqualTo("[[\"2026-10-01\",3]]");
    }

    @Test
    void suggestionsEdgeCases() throws Exception {
        String hit = mvc.perform(get("/api/v1/datasets/patients/fields/city/values?q=PUN")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(hit).contains("Pune").doesNotContain("Nagpur");
        mvc.perform(get("/api/v1/datasets/patients/fields/city/values?q=%25")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/datasets/patients/fields/patient_name/values")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/datasets/patients/fields/aadhaar/values")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/datasets/nope/fields/city/values")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ saved-report validation

    @Test
    void savedReportValidation() throws Exception {
        String q = "{\"dataset\":\"patients\",\"columns\":[\"gender\"]}";
        String[][] cases = {
                {"{\"name\":\"\",\"definition\":{\"query\":" + q + "}}", "Enter a name"},
                {"{\"name\":\"" + "n".repeat(121) + "\",\"definition\":{\"query\":" + q + "}}", "120"},
                {"{\"name\":\"ok\",\"description\":\"" + "d".repeat(401) + "\",\"definition\":{\"query\":" + q + "}}", "400"},
                {"{\"name\":\"ok\"}", "missing"},
                {"{\"name\":\"ok\",\"definition\":{\"view\":{}}}", "missing"},
                {"{\"name\":\"ok\",\"definition\":{\"query\":" + q + ",\"view\":{\"note\":\"" + "x".repeat(50_001) + "\"}}}", "too large"}};
        for (String[] c : cases) {
            MvcResult r = mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(c[0]))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(r.getResponse().getContentAsString()).contains(c[1]);
        }
        mvc.perform(get("/api/v1/reports/999999")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/reports/999999").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"definition\":{\"query\":" + q + "}}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/reports/999999")).andExpect(status().isNotFound());

        // HTML in a name is stored as plain text (the UI renders it as text)
        String xss = "<img src=x onerror=alert(1)>";
        JsonNode saved = JSON.readTree(mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + xss + "\",\"definition\":{\"query\":" + q + "}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(saved.get("name").asText()).isEqualTo(xss);
        mvc.perform(delete("/api/v1/reports/" + saved.get("id").asLong())).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ downloads

    @Test
    void csvIsSafeAndEmptyResultsStillDownload() throws Exception {
        MvcResult empty = mvc.perform(post("/api/v1/query/export").contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"csv\",\"query\":" + detail("{\"field\":\"city\",\"op\":\"eq\",\"value\":\"Atlantis\"}") + "}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(new String(empty.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)).isEqualTo("﻿Reg No\r\n");

        MvcResult all = mvc.perform(post("/api/v1/query/export").contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"csv\",\"title\":\"../../etc/passwd\",\"query\":{\"dataset\":\"patients\",\"columns\":[\"patient_name\",\"is_insured\"]}}"))
                .andExpect(status().isOk()).andReturn();
        // a hostile title cannot change the download path
        assertThat(all.getResponse().getHeader("Content-Disposition")).startsWith("attachment; filename=\"etc_passwd_");
        assertThat(new String(all.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)).contains("Mr. Ravi Rao,Yes");
    }

    @Test
    void pdfRowLimitIsEnforcedBeforeAnyFileIsSent() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:clickhouse://localhost:8123/analytics_test", "admin", "ChAdmin@1234");
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO patient_report (hospital_id, hospital_code, patient_id, registration_no, registration_date, patient_name, gender, version, source_ts) "
                    + "SELECT 1, 'H001', 1000 + number, concat('BULK', toString(number)), toDate('2026-09-01'), 'Bulk', 'Male', 1, now() FROM numbers(50001)");
        }
        try {
            MvcResult r = mvc.perform(post("/api/v1/query/export").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"format\":\"pdf\",\"query\":{\"dataset\":\"patients\",\"columns\":[\"registration_no\"]}}"))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(r.getResponse().getHeader("Content-Disposition")).isNull();
            assertThat(r.getResponse().getContentAsString()).contains("50,004 rows").contains("50,000").contains("CSV");
            // the same report is fine as CSV
            mvc.perform(post("/api/v1/query/export").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"format\":\"csv\",\"query\":{\"dataset\":\"patients\",\"columns\":[\"registration_no\"]}}"))
                    .andExpect(status().isOk());
        } finally {
            AnalyticsTestData.seed();
        }
    }
}
