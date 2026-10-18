package com.hospital.patientreg.identity.model;

import java.time.LocalDateTime;

public record AuditEntry(long auditId, LocalDateTime at, String username, String hospitalCode, String action,
                         String detail, String clientAddress) {
    // getters for JSP EL
    public LocalDateTime getAt() { return at; }
    public String getUsername() { return username; }
    public String getHospitalCode() { return hospitalCode; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public String getClientAddress() { return clientAddress; }
}
