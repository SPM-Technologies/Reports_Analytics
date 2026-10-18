package com.hospital.patientreg.identity.model;

import com.hospital.patientreg.shared.tenant.Permission;
import java.util.EnumSet;
import java.util.Set;

/**
 * The built-in roles, available in every hospital and not editable. Their permissions are written into
 * identity.role at every startup (this enum is the single source). Hospitals add their own roles on the
 * Roles screen ({@link RoleDef}).
 */
public enum Role {
    ADMIN("Hospital administrator", EnumSet.allOf(Permission.class)),
    REGISTRATION("Registration desk", EnumSet.of(Permission.REGISTER, Permission.VIEW_PATIENT, Permission.LEGACY_REPORTS)),
    REPORTS("Reports and analytics", EnumSet.of(Permission.LEGACY_REPORTS, Permission.ANALYTICS, Permission.EXPORT)),
    ANALYST("Analyst (shares reports and formulas)",
            EnumSet.of(Permission.LEGACY_REPORTS, Permission.ANALYTICS, Permission.EXPORT, Permission.PUBLISH_SHARED));

    private final String label;
    private final Set<Permission> permissions;

    Role(String label, Set<Permission> permissions) {
        this.label = label;
        this.permissions = permissions;
    }

    public String getLabel() {
        return label;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
