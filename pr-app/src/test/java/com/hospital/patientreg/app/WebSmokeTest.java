package com.hospital.patientreg.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Smoke test of every screen and download, with the real Spring configuration
 * against the separate "prtest" database (see src/test/resources/db.properties).
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
        @ContextConfiguration(classes = RootConfig.class),
        @ContextConfiguration(classes = WebConfig.class)})
class WebSmokeTest {

    @Autowired
    private WebApplicationContext context;
    /** administrator connection: the application pool is limited by row-level security */
    private final JdbcTemplate jdbc = TestUsers.adminJdbc();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = TestUsers.mvc(context, TestUsers.admin1());
    }

    static MockHttpServletRequestBuilder validRegistration() {
        return validRegistration(1);
    }

    /** A complete, valid registration in department {@code deptId} (which must belong to the user's hospital). */
    static MockHttpServletRequestBuilder validRegistration(int deptId) {
        return post("/patient/save")
                .param("registrationDate", java.time.LocalDate.now().toString())
                .param("patientCategory", "GENERAL").param("title", "Mr.")
                .param("firstName", "Smoke").param("lastName", "Test").param("gender", "Male")
                .param("dateOfBirth", "1990-01-01").param("mobile", "9000000001")
                .param("addressLine1", "1 Test Road").param("city", "Pune").param("state", "Maharashtra")
                .param("pincode", "411001").param("country", "India")
                .param("permSameAsCurrent", "true").param("_permSameAsCurrent", "on")
                .param("emergencyName", "E Test").param("emergencyRelationship", "Father")
                .param("emergencyPhone", "9000000002").param("_hasInsurance", "on")
                .param("deptId", String.valueOf(deptId)).param("consultingDoctor", "Dr Test").param("visitType", "NEW")
                .param("priority", "NORMAL").param("chiefComplaint", "Fever").param("paymentMode", "CASH")
                .param("registrationFee", "200");
    }

    @Test
    void screensRender() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("home"));
        mvc.perform(get("/patient/new")).andExpect(status().isOk()).andExpect(view().name("patient-form"));
        mvc.perform(get("/reports/register")).andExpect(status().isOk()).andExpect(view().name("report-register"))
                .andExpect(model().attributeExists("rows", "total"));
        mvc.perform(get("/reports/summary")).andExpect(status().isOk()).andExpect(view().name("report-summary"));
    }

    @Test
    void validRegistrationIsSavedInAllSixTables() throws Exception {
        MvcResult result = mvc.perform(validRegistration())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/patient/*"))
                .andReturn();
        long id = Long.parseLong(result.getResponse().getRedirectedUrl().substring("/patient/".length()));
        for (String table : new String[] {"patient", "patient_contact", "patient_emergency_contact",
                "patient_insurance", "patient_medical_info", "patient_visit"}) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE patient_id = ?", Integer.class, id);
            assertThat(n).as(table).isEqualTo(1);
        }
        mvc.perform(get("/patient/" + id)).andExpect(status().isOk()).andExpect(view().name("patient-view"));
    }

    @Test
    void invalidRegistrationShowsFormAgainAndSavesNothing() throws Exception {
        Integer before = jdbc.queryForObject("SELECT count(*) FROM patient", Integer.class);
        mvc.perform(validRegistration().param("gender", "Unknown").param("mobile", "12"))
                .andExpect(status().isOk())
                .andExpect(view().name("patient-form"))
                .andExpect(model().attributeHasFieldErrors("patient", "gender", "mobile"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient", Integer.class)).isEqualTo(before);
    }

    @Test
    void downloadsProduceFiles() throws Exception {
        mvc.perform(validRegistration());   // make sure there is at least one row
        assertDownload("/reports/register/export?format=excel", "spreadsheetml", "PK");
        assertDownload("/reports/register/export?format=pdf", "application/pdf", "%PDF");
        assertDownload("/reports/summary/export?format=excel", "spreadsheetml", "PK");
        assertDownload("/reports/summary/export?format=pdf", "application/pdf", "%PDF");
    }

    private void assertDownload(String url, String contentType, String magic) throws Exception {
        MvcResult r = mvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(r.getResponse().getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(r.getResponse().getContentType()).contains(contentType);
        assertThat(new String(r.getResponse().getContentAsByteArray(), 0, magic.length(),
                java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo(magic);
    }

    @Test
    void unknownPatientGivesFriendlyNotFound() throws Exception {
        mvc.perform(get("/patient/999999999")).andExpect(status().isNotFound()).andExpect(view().name("error"));
    }
}
