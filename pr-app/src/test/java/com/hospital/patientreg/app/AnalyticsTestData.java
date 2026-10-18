package com.hospital.patientreg.app;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Known rows in the ClickHouse test database (analytics_test), seeded through the admin account.
 * Hospital H001 (live result): Male 2 patients / Rs 700, Female 1 / Rs 250 (patient 3's newer version
 * counts, patient 4 is deleted), 3 patients and Rs 950 in total, newest change 2026-10-06.
 * Hospital H002: 2 patients (Female, Nashik / Male, Kolhapur), Rs 1,500, newest change 2026-10-07; a user of
 * H001 must never see them.
 */
final class AnalyticsTestData {

    /** Admin account of the local POC ClickHouse (.env); used only to seed analytics_test. */
    static final String ADMIN_PASSWORD = "ChAdmin@1234";

    static final String COLS = "(hospital_id, hospital_code, hospital_name, patient_id, registration_no, registration_date, "
            + "patient_name, gender, age_years, age_band, patient_category, city, state, mobile_masked, dept_id, dept_name, "
            + "consulting_doctor, visit_type, priority, payment_mode, registration_fee, is_insured, version, is_deleted, source_ts)";

    private static final String H1 = "1,'H001','Main Hospital',";
    private static final String H2 = "2,'H002','Test Hospital Two',";

    private AnalyticsTestData() {
    }

    static void seed() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:clickhouse://localhost:8123/analytics_test", "admin", ADMIN_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE patient_report");
            s.execute("INSERT INTO patient_report " + COLS + " VALUES "
                    + "(" + H1 + "1,'REG20260000001','2026-10-01','Mr. Ravi Rao','Male',40,'25-44','GENERAL','Pune','Maharashtra','98XXXXXX10',2,'Cardiology','Dr. Mehta','NEW','NORMAL','UPI',400,1,1,0,'2026-10-01 10:00:00'),"
                    + "(" + H1 + "2,'REG20260000002','2026-10-02','Mr. Amit Shah','Male',30,'25-44','GENERAL','Pune','Maharashtra','97XXXXXX11',2,'Cardiology','Dr. Mehta','NEW','NORMAL','CASH',300,0,1,0,'2026-10-02 10:00:00'),"
                    + "(" + H1 + "3,'REG20260000003','2026-10-03','Ms. Neha Joshi','Female',25,'25-44','GENERAL','Mumbai','Maharashtra','96XXXXXX12',1,'General Medicine','Dr. Iyer','NEW','NORMAL','CARD',200,1,1,0,'2026-10-03 10:00:00'),"
                    + "(" + H1 + "4,'REG20260000004','2026-10-04','Ms. Asha Das','Female',50,'45-59','GENERAL','Delhi','Delhi','95XXXXXX13',6,'ENT','Dr. Bhat','NEW','NORMAL','UPI',100,0,1,0,'2026-10-04 10:00:00')");
            // newer versions: patient 3 moved to Nagpur with a different fee; patient 4 deleted
            s.execute("INSERT INTO patient_report " + COLS + " VALUES "
                    + "(" + H1 + "3,'REG20260000003','2026-10-03','Ms. Neha Joshi','Female',25,'25-44','GENERAL','Nagpur','Maharashtra','96XXXXXX12',1,'General Medicine','Dr. Iyer','NEW','NORMAL','CARD',250,1,2,0,'2026-10-05 10:00:00'),"
                    + "(" + H1 + "4,'REG20260000004','2026-10-04','Ms. Asha Das','Female',50,'45-59','GENERAL','Delhi','Delhi','95XXXXXX13',6,'ENT','Dr. Bhat','NEW','NORMAL','UPI',100,0,2,1,'2026-10-06 10:00:00')");
            // another hospital's patients
            s.execute("INSERT INTO patient_report " + COLS + " VALUES "
                    + "(" + H2 + "5,'H002-REG20260000001','2026-10-02','Ms. Kavya Kulkarni','Female',33,'25-44','GENERAL','Nashik','Maharashtra','94XXXXXX14',11,'General Medicine','Dr. Patil','NEW','NORMAL','UPI',700,1,1,0,'2026-10-07 09:00:00'),"
                    + "(" + H2 + "6,'H002-REG20260000002','2026-10-03','Mr. Rohan Pawar','Male',61,'60+','SENIOR_CITIZEN','Kolhapur','Maharashtra','93XXXXXX15',12,'Cardiology','Dr. Shinde','NEW','NORMAL','CASH',800,0,1,0,'2026-10-07 10:00:00')");
        }
    }
}
