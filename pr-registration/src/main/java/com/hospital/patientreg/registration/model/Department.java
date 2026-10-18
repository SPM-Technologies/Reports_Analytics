package com.hospital.patientreg.registration.model;

public record Department(int deptId, String deptCode, String deptName) {
    // getters for JSP EL, which does not see record accessors as bean properties
    public int getDeptId() { return deptId; }
    public String getDeptCode() { return deptCode; }
    public String getDeptName() { return deptName; }
}
