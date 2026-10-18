package com.hospital.patientreg.shared.tenant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in user of the current request, as every module sees it: the hospitals they are assigned to
 * (with permissions per hospital) and the active hospital they register patients in. Built by the identity
 * module from the session, never from request parameters (architecture section 10.1).
 */
public record Tenant(long userId, String username, String displayName, boolean superAdmin,
                     List<HospitalAccess> hospitals, int activeHospitalId, String clientAddress) {

    public Tenant {
        hospitals = List.copyOf(hospitals);
    }

    /** Every hospital the user is assigned to (PostgreSQL row-level security uses this list). */
    public Set<Integer> hospitalIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        hospitals.forEach(h -> ids.add(h.hospitalId()));
        return ids;
    }

    /** Hospitals where the user has the permission; reports over several hospitals use this list. */
    public Set<Integer> hospitalsWith(Permission p) {
        Set<Integer> ids = new LinkedHashSet<>();
        hospitals.stream().filter(h -> h.allows(p)).forEach(h -> ids.add(h.hospitalId()));
        return ids;
    }

    public Optional<HospitalAccess> hospital(int hospitalId) {
        return hospitals.stream().filter(h -> h.hospitalId() == hospitalId).findFirst();
    }

    public HospitalAccess active() {
        return hospital(activeHospitalId).orElseThrow(() -> forbidden("No hospital selected"));
    }

    public boolean can(Permission p, int hospitalId) {
        return hospital(hospitalId).map(h -> h.allows(p)).orElse(false);
    }

    public boolean canHere(Permission p) {
        return can(p, activeHospitalId);
    }

    public boolean canAnywhere(Permission p) {
        return !hospitalsWith(p).isEmpty();
    }

    /** Throws 403 unless the user has the permission in the active hospital. */
    public HospitalAccess requireHere(Permission p) {
        if (!canHere(p)) {
            throw forbidden("You do not have permission for this in " + active().name());
        }
        return active();
    }

    /** Throws 403 unless the user has the permission in at least one hospital; returns those hospitals. */
    public Set<Integer> requireAnywhere(Permission p) {
        Set<Integer> ids = hospitalsWith(p);
        if (ids.isEmpty()) {
            throw forbidden("You do not have permission for this");
        }
        return ids;
    }

    public boolean multiHospital() {
        return hospitals.size() > 1;
    }

    // ---- for JSP EL (method calls with a String argument; records' accessors are not bean properties)

    public boolean allowedHere(String permission) {
        return canHere(Permission.valueOf(permission));
    }

    public boolean allowedAnywhere(String permission) {
        return canAnywhere(Permission.valueOf(permission));
    }

    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public boolean isSuperAdmin() { return superAdmin; }
    public List<HospitalAccess> getHospitals() { return hospitals; }
    public int getActiveHospitalId() { return activeHospitalId; }
    public HospitalAccess getActive() { return active(); }
    public boolean isMultiHospital() { return multiHospital(); }

    public static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
}
