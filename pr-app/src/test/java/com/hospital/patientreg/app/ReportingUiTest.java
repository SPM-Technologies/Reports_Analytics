package com.hospital.patientreg.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/** Stage 3 backend: serving the React app, saved reports and downloads (against the test databases). */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
        @ContextConfiguration(classes = RootConfig.class),
        @ContextConfiguration(classes = WebConfig.class)})
class ReportingUiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUMMARY_QUERY = """
            {"dataset":"patients","columns":["gender"],
             "measures":[{"field":"patients"},{"field":"registration_fee","agg":"sum"}],
             "sort":[{"field":"gender","dir":"desc"}]}""";

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

    @Test
    void reactAppIsServedFromTheWar() throws Exception {
        // MockMvc records forwards instead of following them: /app/ must forward to index.html
        mvc.perform(get("/app/")).andExpect(status().isOk()).andExpect(forwardedUrl("/app/index.html"));
        MvcResult indexResult = mvc.perform(get("/app/index.html")).andExpect(status().isOk()).andReturn();
        assertThat(indexResult.getResponse().getContentAsString()).contains("<div id=\"root\"></div>").contains("./assets/");
        assertThat(indexResult.getResponse().getHeader("Cache-Control")).contains("no-cache");
        // app routes fall back to index.html; missing files stay 404
        assertThat(mvc.perform(get("/app/reports/42")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).contains("<div id=\"root\">");
        mvc.perform(get("/app/assets/does-not-exist.js")).andExpect(status().isNotFound());
        mvc.perform(get("/app")).andExpect(status().is3xxRedirection());
    }

    @Test
    void templatesAreListedAndReadOnly() throws Exception {
        JsonNode list = json(mvc.perform(get("/api/v1/reports")).andExpect(status().isOk()).andReturn());
        assertThat(list.findValuesAsText("name")).contains("Patient Register (Report 1)", "Department Summary (Report 2)",
                "Patients by Department and Gender", "Monthly Registrations");
        long templateId = list.get(0).get("id").asLong();
        assertThat(list.get(0).get("template").asBoolean()).isTrue();

        mvc.perform(delete("/api/v1/reports/" + templateId)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/reports/" + templateId).contentType(MediaType.APPLICATION_JSON)
                .content(saveBody("Hacked", SUMMARY_QUERY))).andExpect(status().isBadRequest());

        // every template must still run against the current catalogue
        for (JsonNode t : list) {
            if (t.get("template").asBoolean()) {
                JsonNode full = json(mvc.perform(get("/api/v1/reports/" + t.get("id").asLong())).andReturn());
                mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content(full.get("definition").get("query").toString())).andExpect(status().isOk());
            }
        }
    }

    @Test
    void savedReportLifecycle() throws Exception {
        JsonNode created = json(mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON)
                .content(saveBody("Gender test report", SUMMARY_QUERY))).andExpect(status().isCreated()).andReturn());
        long id = created.get("id").asLong();
        assertThat(created.get("template").asBoolean()).isFalse();
        assertThat(created.get("definition").get("query").get("columns").get(0).asText()).isEqualTo("gender");

        mvc.perform(put("/api/v1/reports/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(saveBody("Gender test report (renamed)", SUMMARY_QUERY))).andExpect(status().isOk());
        assertThat(json(mvc.perform(get("/api/v1/reports/" + id)).andReturn()).get("name").asText())
                .isEqualTo("Gender test report (renamed)");

        // an invalid definition is never stored
        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON)
                .content(saveBody("Bad", "{\"dataset\":\"patients\",\"columns\":[\"aadhaar\"]}"))).andExpect(status().isBadRequest());

        mvc.perform(delete("/api/v1/reports/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/reports/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void downloadsMatchWhatIsOnScreen() throws Exception {
        MvcResult csv = export("csv");
        assertThat(csv.getResponse().getHeader("Content-Disposition")).startsWith("attachment; filename=\"gender_test_");
        String text = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Gender,Patients,Total Fee (Rs)\r\n")
                .contains("Male,2,700").contains("Female,1,250").contains("TOTAL,3,950");

        assertThat(new String(export("xlsx").getResponse().getContentAsByteArray(), 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        assertThat(new String(export("pdf").getResponse().getContentAsByteArray(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        mvc.perform(post("/api/v1/query/export").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":" + SUMMARY_QUERY + ",\"format\":\"docx\"}")).andExpect(status().isBadRequest());
    }

    private MvcResult export(String format) throws Exception {
        return mvc.perform(post("/api/v1/query/export").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":" + SUMMARY_QUERY + ",\"format\":\"" + format + "\",\"title\":\"Gender test\"}"))
                .andExpect(status().isOk()).andReturn();
    }

    private static String saveBody(String name, String query) {
        return "{\"name\":\"" + name + "\",\"definition\":{\"query\":" + query + ",\"view\":{\"mode\":\"table\"}}}";
    }

    private static JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }
}
