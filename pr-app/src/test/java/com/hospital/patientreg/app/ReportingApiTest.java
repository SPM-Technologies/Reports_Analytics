package com.hospital.patientreg.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Reporting API end to end against the real ClickHouse test database (analytics_test).
 * Seed data covers the tricky cases: a patient with two versions (only the newest counts)
 * and a deleted patient (must disappear).
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
        @ContextConfiguration(classes = RootConfig.class),
        @ContextConfiguration(classes = WebConfig.class)})
class ReportingApiTest {

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

    private JsonNode query(String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void aggregateUsesLatestVersionAndSkipsDeleted() throws Exception {
        JsonNode r = query("""
                {"dataset":"patients","columns":["gender"],
                 "measures":[{"field":"patients"},{"field":"registration_fee","agg":"sum"}],
                 "sort":[{"field":"gender","dir":"desc"}]}""");
        assertThat(r.get("mode").asText()).isEqualTo("aggregate");
        JsonNode rows = r.get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get(0).asText()).isEqualTo("Male");
        assertThat(rows.get(0).get(1).asLong()).isEqualTo(2);
        assertThat(rows.get(0).get(2).decimalValue()).isEqualByComparingTo("700");
        assertThat(rows.get(1).get(0).asText()).isEqualTo("Female");
        assertThat(rows.get(1).get(1).asLong()).isEqualTo(1);
        assertThat(rows.get(1).get(2).decimalValue()).isEqualByComparingTo("250");
        assertThat(r.get("totals").get(0).asLong()).isEqualTo(3);
        assertThat(r.get("totals").get(1).decimalValue()).isEqualByComparingTo("950");
        assertThat(r.get("totalRows").asLong()).isEqualTo(2);
        assertThat(r.get("dataAsOf").asText()).startsWith("2026-10-06");
    }

    @Test
    void detailListWithFiltersAndDates() throws Exception {
        JsonNode r = query("""
                {"dataset":"patients","columns":["registration_no","registration_date","city"],
                 "filter":{"and":[{"field":"state","op":"eq","value":"Maharashtra"},
                                  {"field":"patient_name","op":"contains","value":"NEHA"}]}}""");
        assertThat(r.get("totalRows").asLong()).isEqualTo(1);
        assertThat(r.get("rows").toString()).isEqualTo("[[\"REG20260000003\",\"2026-10-03\",\"Nagpur\"]]");
    }

    @Test
    void invalidDefinitionReturnsProblemJson() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataset\":\"patients\",\"columns\":[\"aadhaar\"]}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
        JsonNode body = JSON.readTree(r.getResponse().getContentAsString());
        assertThat(body.get("title").asText()).isEqualTo("Invalid report definition");
        assertThat(body.get("detail").asText()).contains("Unknown field");

        mvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void catalogueAndSuggestions() throws Exception {
        JsonNode ds = JSON.readTree(mvc.perform(get("/api/v1/datasets")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(ds.get(0).get("id").asText()).isEqualTo("patients");
        assertThat(ds.get(0).get("fields").toString()).contains("\"registration_no\"").doesNotContain("\"patient_id\"");

        JsonNode values = JSON.readTree(mvc.perform(get("/api/v1/datasets/patients/fields/city/values"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(values.get(0).get("value").asText()).isEqualTo("Pune");
        assertThat(values.get(0).get("count").asLong()).isEqualTo(2);
        assertThat(values.toString()).doesNotContain("Delhi");   // deleted patient is not suggested
    }
}
