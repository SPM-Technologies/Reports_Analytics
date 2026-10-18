package com.hospital.patientreg.identity.model;

import java.time.LocalDateTime;

public record Hospital(int hospitalId, String code, String name, String city, boolean active, LocalDateTime createdAt) {
    // getters for JSP EL
    public int getHospitalId() { return hospitalId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
