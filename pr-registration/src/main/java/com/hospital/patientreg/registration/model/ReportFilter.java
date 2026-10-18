package com.hospital.patientreg.registration.model;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** Query filters shared by both reports. Blank values mean "all". */
public class ReportFilter {

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fromDate;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate toDate;
    private Integer deptId;
    private String gender;
    private String patientCategory;
    private String keyword;
    private int page = 1;
    private int pageSize = 50;

    public int getOffset() {
        return (Math.max(page, 1) - 1) * pageSize;
    }

    /** Human-readable summary printed in exported files. */
    public String describe(String deptName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Period: ").append(fromDate != null ? fromDate : "Beginning")
          .append(" to ").append(toDate != null ? toDate : "Today");
        if (deptName != null) sb.append(" | Department: ").append(deptName);
        if (hasText(gender)) sb.append(" | Gender: ").append(gender);
        if (hasText(patientCategory)) sb.append(" | Category: ").append(patientCategory);
        if (hasText(keyword)) sb.append(" | Search: ").append(keyword);
        return sb.toString();
    }

    public static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public Integer getDeptId() { return deptId; }
    public void setDeptId(Integer deptId) { this.deptId = deptId; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getPatientCategory() { return patientCategory; }
    public void setPatientCategory(String patientCategory) { this.patientCategory = patientCategory; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
