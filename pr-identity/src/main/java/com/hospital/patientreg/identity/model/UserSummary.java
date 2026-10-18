package com.hospital.patientreg.identity.model;

import java.time.LocalDateTime;
import java.util.List;

/** A user as shown on the administration screens (never the password hash). */
public record UserSummary(long userId, String username, String fullName, boolean superAdmin, boolean active,
                          LocalDateTime lockedUntil, LocalDateTime lastLoginAt, List<Assignment> assignments) {

    /** One hospital role of the user. */
    public record Assignment(int hospitalId, String hospitalCode, String hospitalName, RoleDef role) {
        public int getHospitalId() { return hospitalId; }
        public String getHospitalCode() { return hospitalCode; }
        public String getHospitalName() { return hospitalName; }
        public RoleDef getRole() { return role; }
    }

    public boolean locked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    public RoleDef roleIn(int hospitalId) {
        return assignments.stream().filter(a -> a.hospitalId() == hospitalId).map(Assignment::role).findFirst().orElse(null);
    }

    // getters for JSP EL
    public long getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getFullName() { return fullName; }
    public boolean isSuperAdmin() { return superAdmin; }
    public boolean isActive() { return active; }
    public boolean isLocked() { return locked(); }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public List<Assignment> getAssignments() { return assignments; }
}
