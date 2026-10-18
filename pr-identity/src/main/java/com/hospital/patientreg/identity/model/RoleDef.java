package com.hospital.patientreg.identity.model;

import com.hospital.patientreg.shared.tenant.Permission;
import java.util.Set;

/** A role as stored in identity.role: built-in (hospitalId null, every hospital) or a hospital's own role. */
public record RoleDef(String key, Integer hospitalId, String label, Set<Permission> permissions, boolean builtin) {

    public RoleDef {
        permissions = Set.copyOf(permissions);
    }

    /** Whether this role may be given to a user in that hospital. */
    public boolean usableIn(int hospital) {
        return hospitalId == null || hospitalId == hospital;
    }

    // getters for JSP EL
    public String getKey() { return key; }
    public Integer getHospitalId() { return hospitalId; }
    public String getLabel() { return label; }
    public boolean isBuiltin() { return builtin; }
    public Set<Permission> getPermissions() { return permissions; }
    public boolean has(String permission) { return permissions.contains(Permission.valueOf(permission)); }

    @Override
    public String toString() {
        return key;
    }
}
