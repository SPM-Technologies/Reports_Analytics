package com.hospital.patientreg.registration.model;

public record Doctor(int doctorId, int deptId, String name, boolean active) {
    // getters for JSP EL
    public int getDoctorId() { return doctorId; }
    public int getDeptId() { return deptId; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
