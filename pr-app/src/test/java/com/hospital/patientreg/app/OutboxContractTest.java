package com.hospital.patientreg.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The real-time pipeline contract: every registration writes one PatientSnapshot.v2 outbox event to the
 * WAL (which Debezium reads) in the same transaction, and leaves the outbox table empty. Read here through
 * a temporary logical-decoding slot, as Debezium would. Needs wal_level=logical (docker-compose.yml).
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
        @ContextConfiguration(classes = RootConfig.class),
        @ContextConfiguration(classes = WebConfig.class)})
class OutboxContractTest {

    private static final String SLOT = "outbox_contract_test";

    @Autowired
    private WebApplicationContext context;
    /** administrator connection: the application pool is limited by row-level security */
    private final JdbcTemplate jdbc = TestUsers.adminJdbc();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = TestUsers.mvc(context, TestUsers.admin1());
        dropSlot();
        jdbc.queryForList("SELECT pg_create_logical_replication_slot(?, 'test_decoding')", SLOT);
    }

    @AfterEach
    void dropSlot() {
        jdbc.queryForList("SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = ?", SLOT);
    }

    @Test
    void registrationPublishesOneSnapshotEventAndLeavesOutboxEmpty() throws Exception {
        String location = mvc.perform(WebSmokeTest.validRegistration().param("firstName", "Outbox"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        List<String> changes = jdbc.queryForList(
                "SELECT data FROM pg_logical_slot_get_changes(?, NULL, NULL) WHERE data LIKE 'table patientreg.report_outbox:%'",
                String.class, SLOT);
        List<String> inserts = changes.stream()
                .filter(c -> c.contains(": INSERT:") && c.contains("aggregateid[character varying]:'" + id + "'"))
                .toList();

        assertThat(inserts).hasSize(1);
        String event = inserts.getFirst();
        assertThat(event).contains("aggregatetype[character varying]:'patient'", "type[character varying]:'PatientSnapshot.v2'",
                "\"patient_id\": " + id, "\"registration_no\": \"H001-REG", "\"mobile_masked\": \"90XXXXXX01\"", "\"version\": 1", "\"hospital_id\": 1", "\"hospital_code\": \"H001\"",
                "\"is_deleted\": 0", "\"source_ts\": \"");
        assertThat(event).doesNotContain("national_id", "9000000001");   // no Aadhaar, no full mobile
        assertThat(changes).anyMatch(c -> c.contains(": DELETE:"));      // the row is removed in the same transaction
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_outbox", Integer.class)).isZero();
    }
}
