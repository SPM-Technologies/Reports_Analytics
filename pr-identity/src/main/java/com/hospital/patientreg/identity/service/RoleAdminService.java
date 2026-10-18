package com.hospital.patientreg.identity.service;

import com.hospital.patientreg.identity.dao.IdentityDao;
import com.hospital.patientreg.identity.model.Hospital;
import com.hospital.patientreg.identity.model.RoleDef;
import com.hospital.patientreg.identity.security.AccessVersion;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hospitals' own roles (architecture 10.2: "create roles; assign permissions"). A hospital administrator
 * defines roles for the hospitals they manage, as any set of permissions; built-in roles cannot be changed.
 * A role in use cannot be deleted. Changes reach signed-in users on their next request.
 */
@Service
public class RoleAdminService {

    private final IdentityDao dao;
    private final UserAdminService users;
    private final AuditTrail audit;
    private final AccessVersion accessVersion;

    public RoleAdminService(IdentityDao dao, UserAdminService users, AuditTrail audit, AccessVersion accessVersion) {
        this.dao = dao;
        this.users = users;
        this.audit = audit;
        this.accessVersion = accessVersion;
    }

    public List<RoleDef> list(Tenant t) {
        return users.assignableRoles(t);
    }

    /** A role the administrator may change: one of their hospitals' own roles. */
    public RoleDef editable(Tenant t, String key) {
        Set<Integer> mine = manageable(t);
        RoleDef role = dao.role(key).filter(r -> !r.builtin() && r.hospitalId() != null && mine.contains(r.hospitalId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found"));
        // nobody changes their own access: a role the administrator holds there is changed by someone else
        if (!t.isSuperAdmin() && t.hospital(role.hospitalId()).map(h -> key.equals(h.role())).orElse(false)) {
            throw Tenant.forbidden("You hold this role yourself; another administrator must change it");
        }
        return role;
    }

    /** A role may only grant permissions the administrator has in that hospital (no escalation through roles). */
    private static Set<Permission> grantable(Tenant t, int hospitalId, Set<Permission> perms) {
        if (!t.isSuperAdmin()) {
            for (Permission p : perms) {
                if (!t.can(p, hospitalId)) {
                    throw Tenant.forbidden("You cannot give the permission " + p + " because you do not have it there");
                }
            }
        }
        return perms;
    }

    @Transactional
    public String create(Tenant t, int hospitalId, String label, Collection<String> permissions) {
        if (!manageable(t).contains(hospitalId)) {
            throw Tenant.forbidden("You do not manage that hospital");
        }
        String name = label(label);
        String base = "H" + hospitalId + "_" + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        base = base.length() > 34 ? base.substring(0, 34) : base;
        Set<Permission> perms = grantable(t, hospitalId, permissions(permissions));
        // a plain insert: two administrators creating the same name at once get two keys, never one overwritten
        String key = base;
        for (int i = 2; !dao.insertRole(key, hospitalId, name, perms); i++) {
            key = base + "_" + i;
        }
        audit.record("ROLE_CHANGED", hospitalId, "created " + key + " " + perms);
        return key;
    }

    @Transactional
    public void update(Tenant t, String key, String label, Collection<String> permissions) {
        RoleDef r = editable(t, key);
        Set<Permission> perms = grantable(t, r.hospitalId(), permissions(permissions));
        dao.saveRole(key, r.hospitalId(), label(label), perms, false);
        audit.record("ROLE_CHANGED", r.hospitalId(), "updated " + key + ": " + r.permissions() + " -> " + perms);
        accessVersion.changed();
    }

    @Transactional
    public void delete(Tenant t, String key) {
        RoleDef r = editable(t, key);
        long users = dao.roleUsage(key);
        if (users > 0) {
            throw new InvalidInputException("This role is given to " + users + " user(s); give them another role first");
        }
        dao.deleteRole(key);
        audit.record("ROLE_CHANGED", r.hospitalId(), "deleted " + key);
    }

    private Set<Integer> manageable(Tenant t) {
        return users.manageableHospitals(t).stream().map(Hospital::hospitalId).collect(Collectors.toSet());
    }

    private static String label(String label) {
        String l = label == null ? "" : label.trim();
        if (l.length() < 2 || l.length() > 60) {
            throw new InvalidInputException("Role name: 2-60 characters");
        }
        return l;
    }

    private static Set<Permission> permissions(Collection<String> names) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        if (names != null) {
            for (String n : names) {
                try {
                    out.add(Permission.valueOf(n));
                } catch (IllegalArgumentException e) {
                    throw new InvalidInputException("Unknown permission " + n);
                }
            }
        }
        if (out.isEmpty()) {
            throw new InvalidInputException("Choose at least one permission");
        }
        return out;
    }
}
