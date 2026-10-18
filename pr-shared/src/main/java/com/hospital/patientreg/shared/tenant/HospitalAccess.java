package com.hospital.patientreg.shared.tenant;

import java.io.Serializable;
import java.util.Set;

/** One hospital a user is assigned to, with the user's role there and what it allows. */
public record HospitalAccess(int hospitalId, String code, String name, String role, Set<Permission> permissions)
        implements Serializable {

    public HospitalAccess {
        permissions = Set.copyOf(permissions);
    }

    public boolean allows(Permission p) {
        return permissions.contains(p);
    }

    // getters for JSP EL, which does not see record accessors as bean properties
    public int getHospitalId() { return hospitalId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getRole() { return role; }
}
